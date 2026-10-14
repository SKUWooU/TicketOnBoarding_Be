-- Single-node bounded admission core. No MariaDB transaction or rollback semantics.
local op, fingerprint = ARGV[1], ARGV[2]
local opens, maxActive, maxWaiting = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])
local waitMs, admitMs, batch = tonumber(ARGV[6]), tonumber(ARGV[7]), tonumber(ARGV[8])
local user, entryId, admissionId, token = ARGV[9], ARGV[10], ARGV[11], ARGV[12]
local now
if ARGV[13] == '' then
  local t = redis.call('TIME'); now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
else now = tonumber(ARGV[13]) end
if not now or now < 0 or not opens or not maxActive or not maxWaiting or not waitMs or not admitMs or not batch then
  return redis.error_reply('ADMISSION_INVALID_INPUT')
end
if op ~= 'INIT' and op ~= 'JOIN' and op ~= 'READ' and op ~= 'LEAVE' and op ~= 'TICK' and op ~= 'VERIFY' then
  return redis.error_reply('ADMISSION_INVALID_OPERATION')
end
local types = {'hash', 'hash', 'zset', 'zset', 'zset', 'zset'}
for i = 1, 6 do
  local actual = redis.call('TYPE', KEYS[i]).ok
  if actual ~= 'none' and actual ~= types[i] then return redis.error_reply('ADMISSION_WRONG_TYPE') end
end
local configured = redis.call('HGET', KEYS[1], 'policy')
if op == 'INIT' then
  if configured then
    if configured ~= fingerprint then return redis.error_reply('ADMISSION_POLICY_MISMATCH') end
  else
    for i = 1, 6 do
      if redis.call('EXISTS', KEYS[i]) ~= 0 then return redis.error_reply('ADMISSION_PARTIAL_STATE') end
    end
    redis.call('HSET', KEYS[1], 'policy', fingerprint, 'seq', '0', 'stateCount', '0')
    return 'ok'
  end
end
if configured ~= fingerprint then return redis.error_reply('ADMISSION_UNAVAILABLE') end
local rawSeq = redis.call('HGET', KEYS[1], 'seq')
local seq = tonumber(rawSeq)
if not rawSeq or not string.match(rawSeq, '^%d+$') or (#rawSeq > 1 and string.sub(rawSeq, 1, 1) == '0')
    or not seq or seq < 0 or seq > 9007199254740990 then return redis.error_reply('ADMISSION_SEQUENCE_INVALID') end
local count = redis.call('HLEN', KEYS[2])
if tonumber(redis.call('HGET', KEYS[1], 'stateCount')) ~= count
    or count ~= redis.call('ZCARD', KEYS[3]) + redis.call('ZCARD', KEYS[5]) + redis.call('ZCARD', KEYS[6])
    or redis.call('ZCARD', KEYS[3]) ~= redis.call('ZCARD', KEYS[4]) then
  return redis.error_reply('ADMISSION_INDEX_INCONSISTENT')
end
if op == 'INIT' then return 'ok' end
local cache = {}
local function load(member)
  if cache[member] then return cache[member] end
  local raw = redis.call('HGET', KEYS[2], member)
  if not raw then return nil end
  local state = cjson.decode(raw)
  if type(state.id) ~= 'string' or type(state.admissionId) ~= 'string' or type(state.token) ~= 'string'
      or type(state.expiry) ~= 'number' or type(state.seq) ~= 'string' or not tonumber(state.seq)
      or (state.state ~= 'WAITING' and state.state ~= 'ADMITTED' and state.state ~= 'EXPIRED' and state.state ~= 'LEFT') then
    error('ADMISSION_CORRUPT_STATE')
  end
  local waiting = redis.call('ZSCORE', KEYS[3], member)
  local waitingExpiry = redis.call('ZSCORE', KEYS[4], member)
  local active = redis.call('ZSCORE', KEYS[5], member)
  local ended = redis.call('ZSCORE', KEYS[6], member)
  if (state.state == 'WAITING' and (tonumber(waiting) ~= tonumber(state.seq) or tonumber(waitingExpiry) ~= state.expiry or active or ended))
      or (state.state == 'ADMITTED' and (tonumber(active) ~= state.expiry or waiting or waitingExpiry or ended))
      or ((state.state == 'LEFT' or state.state == 'EXPIRED') and (not ended or waiting or waitingExpiry or active)) then
    error('ADMISSION_STATE_INDEX_MISMATCH')
  end
  cache[member] = state; return state
end
local function live(state)
  return state and (state.state == 'WAITING' or state.state == 'ADMITTED') and state.expiry > now
end
local function view(member)
  local state = load(member)
  if not state then return 'null' end
  if (state.state == 'WAITING' or state.state == 'ADMITTED') and state.expiry <= now then state.state = 'EXPIRED' end
  local rank = state.state == 'WAITING' and redis.call('ZRANK', KEYS[3], member) or false
  state.position = rank and rank + 1 or 0
  state.serverNow = now
  return cjson.encode(state)
end
local current = user ~= '' and load(user) or nil
if op == 'READ' then return view(user) end
if op == 'VERIFY' then
  return current and current.state == 'ADMITTED' and current.expiry > now
      and current.admissionId == admissionId and current.token == token and 'true' or 'false'
end
if op == 'LEAVE' and (not current or current.id ~= entryId or not live(current)) then return 'ok' end
local needsJoin = op == 'JOIN' and not live(current)
if needsJoin and redis.call('ZCOUNT', KEYS[4], '(' .. now, '+inf') >= maxWaiting then
  return redis.error_reply('ADMISSION_QUEUE_FULL')
end
if needsJoin and seq >= 9007199254740990 then return redis.error_reply('ADMISSION_SEQUENCE_EXHAUSTED') end

-- Parse every state this bounded mutation may touch before its first write.
local expiredWaiting = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', now, 'LIMIT', 0, batch)
local expiredActive = redis.call('ZRANGEBYSCORE', KEYS[5], '-inf', now, 'LIMIT', 0, batch)
local expiredTerminal = redis.call('ZRANGEBYSCORE', KEYS[6], '-inf', now, 'LIMIT', 0, batch)
local candidates = op == 'TICK' and redis.call('ZRANGE', KEYS[3], 0, batch - 1) or {}
for _, list in ipairs({expiredWaiting, expiredActive, expiredTerminal, candidates}) do
  for _, member in ipairs(list) do
    if not load(member) then return redis.error_reply('ADMISSION_MISSING_STATE') end
  end
end
local function save(member, state)
  cache[member] = state; state.position = nil
  redis.call('HSET', KEYS[2], member, cjson.encode(state))
end
local function terminal(member, state, status)
  redis.call('ZREM', KEYS[3], member); redis.call('ZREM', KEYS[4], member); redis.call('ZREM', KEYS[5], member)
  state.state = status; save(member, state)
  redis.call('ZADD', KEYS[6], now + waitMs + admitMs, member)
end
for _, member in ipairs(expiredTerminal) do
  local state = load(member)
  if state.state ~= 'LEFT' and state.state ~= 'EXPIRED' then return redis.error_reply('ADMISSION_TERMINAL_INVALID') end
end
-- All validation above; no semantic errors are raised after writes begin.
for _, member in ipairs(expiredTerminal) do
  redis.call('ZREM', KEYS[6], member); redis.call('HDEL', KEYS[2], member); cache[member] = nil
end
for _, list in ipairs({expiredWaiting, expiredActive}) do
  for _, member in ipairs(list) do
    local state = load(member)
    if state and state.expiry <= now then terminal(member, state, 'EXPIRED') end
  end
end
if op == 'JOIN' then
  if needsJoin then
    local old = load(user)
    if old and (old.state == 'WAITING' or old.state == 'ADMITTED') then terminal(user, old, 'EXPIRED') end
    local nextSeq = redis.call('HINCRBY', KEYS[1], 'seq', 1)
    save(user, {id = entryId, admissionId = admissionId, token = token, state = 'WAITING', seq = redis.call('HGET', KEYS[1], 'seq'), expiry = now + waitMs})
    redis.call('ZREM', KEYS[6], user)
    redis.call('ZADD', KEYS[3], nextSeq, user); redis.call('ZADD', KEYS[4], now + waitMs, user)
  end
elseif op == 'LEAVE' then terminal(user, load(user), 'LEFT') end
local admitted = 0
if op == 'TICK' and now >= opens then
  local slots = maxActive - redis.call('ZCOUNT', KEYS[5], '(' .. now, '+inf')
  for _, member in ipairs(candidates) do
    local state = load(member)
    if slots > 0 and state and state.state == 'WAITING' and state.expiry > now then
      redis.call('ZREM', KEYS[3], member); redis.call('ZREM', KEYS[4], member)
      state.state = 'ADMITTED'; state.expiry = now + admitMs; save(member, state)
      redis.call('ZADD', KEYS[5], state.expiry, member); slots = slots - 1; admitted = admitted + 1
    end
  end
end
-- Terminal states have a time bound AND cardinality bound; hot join/leave cannot grow a hash forever.
local overflow = redis.call('ZCARD', KEYS[6]) - maxWaiting - maxActive
if overflow > 0 then
  local evicted = redis.call('ZPOPMIN', KEYS[6], math.min(overflow, batch * 2 + 1))
  for i = 1, #evicted, 2 do redis.call('HDEL', KEYS[2], evicted[i]); cache[evicted[i]] = nil end
end
redis.call('HSET', KEYS[1], 'stateCount', redis.call('HLEN', KEYS[2]))
if op == 'JOIN' then return view(user) end
if op == 'TICK' then return tostring(admitted) end
return 'ok'
