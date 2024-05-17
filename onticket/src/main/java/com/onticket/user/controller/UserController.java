package com.onticket.user.controller;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.form.UserChangePwdForm;
import com.onticket.user.form.UserFindIdForm;
import com.onticket.user.form.UserLoginForm;
import com.onticket.user.form.UserCreateForm;
import com.onticket.user.repository.UserRepository;
import com.onticket.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;


    //인증번호 캐싱
    private String compare_phone;


    //회원가입
    @PostMapping("/signup")
    public ResponseEntity<String> CreateUser(@Valid @RequestBody UserCreateForm userCreateForm, BindingResult bindingResult){

        try {
            //필드값 비어있거나 값이 옳바르지 않을떄
            if (bindingResult.hasErrors()) {
                //회원가입폼에 있는 에러메세지 읽어옴
                String errorMessage = bindingResult.getFieldError().getDefaultMessage();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
            }
            //비밀번호2개가 값이 다를때
            if(!userCreateForm.getPassword1().equals(userCreateForm.getPassword2())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("비밀번호가 일치하지 않습니다.");
            }
            //인증번호가 보내진 번호와 회원가입 버튼 눌렀을 때 받은 번호가 다를때
            if(!userCreateForm.getPhonenumber().equals(compare_phone)){
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("다시 인증해주세요.");
            }
            //보낸 코드와 일치 하지 않을때
            if(!userCreateForm.getSmscode().equals(userService.GetSMSCode())){
                return  ResponseEntity.status(HttpStatus.BAD_REQUEST).body("올바르자 얺은 코드입니다. 다시 확인하세요.");
            }


            //통과시 db 생성
            userService.Create(userCreateForm);
            return ResponseEntity.ok("회원가입에 성공했습니다.");
            //중복시
        } catch (Exception e) {
            // 예외 발생 시 예외 정보와 함께 400 Bad Request 응답 반환
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("회원가입에 실패했습니다.");
        }
    }

    //아이디 중복검사
    @PostMapping("/signup/check")
    public ResponseEntity<String> IdCheck(@RequestBody Map<String, String> requestBody){
        //따로 폼 만들지 않고 맵으로 단일 json 처리
        String username = requestBody.get("username");
        boolean status= userService.IsExist(username);
        if (status){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("중복된 아이디입니다.");
        } else return ResponseEntity.ok("중복검사를 통과했습니다.");
    }

    //회원가입 sms 인증
    @PostMapping("/smscode")
    public ResponseEntity<String> SendSMSCode(@RequestBody Map<String, String> requestBody){
        try{
            //전화번호 읽기
            String to=requestBody.get("to");
            //코드생성후 메세지 보내기
            String smscode=userService.SendSMSCode(to);
            //캐싱
            compare_phone=to;
            //프론트에 데이터 보내기
            String jsonData="{\"smscode\":\""+smscode+"\"}";
            return new ResponseEntity<>(jsonData,HttpStatus.OK);
        } catch (Exception e){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("다시 시도하세요.");
        }

    }

    //로그인
    @PostMapping("/login")
    public ResponseEntity<String> Login(@Valid @RequestBody UserLoginForm userLoginForm, BindingResult bindingResult){
        try{
            //유효성검사
            if (bindingResult.hasErrors()) {
                //로그인폼에 있는 에러메세지 받아옴-> 빈값이면 메세지 출력
                String errorMessage = bindingResult.getFieldError().getDefaultMessage();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
            }
            //AuthenticationManager 를 사용하여 아이디 패스워드 인증처리
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(userLoginForm.getUsername(), userLoginForm.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);


            return ResponseEntity.ok("로그인에 성공했습니다.");
        } catch (BadCredentialsException e){
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("아이디 또는 비밀번호가 올바르지 않습니다.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("로그인에 실패했습니다.");
        }
    }

    //아이디 찾기
    @PostMapping("/findid")
    public ResponseEntity<String> FindId(@Valid @RequestBody UserFindIdForm userFindIdForm, BindingResult bindingResult){
        try {
            if(bindingResult.hasErrors()) {
                String errorMessage = bindingResult.getFieldError().getDefaultMessage();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
            }
            String phonenumber = userFindIdForm.getPhonenumber();
            String email = userFindIdForm.getEmail();
            Optional<SiteUser> siteUser = userRepository.findSiteUserByPhonenumberAndEmail(phonenumber, email);
            if (siteUser.isPresent()) {
                return ResponseEntity.ok(siteUser.get().getUsername());
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("등록된 아이디가 없습니다.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("아이디 찾기에 실패했습니다.");
        }
    }

    //패스워드 변경시 사용하는 sms 인증
    @PostMapping("/issmscode")
    public ResponseEntity<String> CodeCheck(@RequestBody Map<String, String> requestBody){
        Optional<SiteUser> siteUser=userRepository.findOptionalSiteUserByPhonenumber(compare_phone);
        //코드 대조하고 해당 번호를 가진 유저가 있으면 성공
        if(userService.GetSMSCode().equals(requestBody.get("smscode"))||siteUser.isPresent()){
            return ResponseEntity.ok("인증에 성공하였습니다.");
        } else return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("인증에 실패했습니다.");
    }

    //패스워드 변경
    //****프론트에서 번호도 전달받아야함*****
    @PostMapping("/changepwd")
    public ResponseEntity<String> ChangePwd(@Valid @RequestBody UserChangePwdForm userChangePwdForm, BindingResult bindingResult){
        //의존성검사
        if(bindingResult.hasErrors()) {
            String errorMessage = bindingResult.getFieldError().getDefaultMessage();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMessage);
        }
        String phonenumber = userChangePwdForm.getPhonenumber();
        //입력한 비밀번호 두개가 같으면 로직처리
        if (userChangePwdForm.getPassword1().equals(userChangePwdForm.getPassword2())) {
            String newpassword = userChangePwdForm.getPassword1();
            userService.ChangePassword(phonenumber,newpassword);
            return ResponseEntity.ok("비밀번호가 변경되었습니다.");
        } else return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("비밀번호가 다릅니다.");
    }

    @PostMapping("/deleteid")
    public ResponseEntity<String> DeleteId(){
        //현재 인증된 사용자의 ID를 받아서 삭제
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        SiteUser siteUser = userRepository.findByUsername(username);
        userService.Delete(siteUser);
        return ResponseEntity.ok("탈퇴성공");
    }
}

// 응답 데이터를 JSON 형식으로 생성
//String jsonData = "{\"message\": \"Hello, world!\"}";
//
//ResponseEntity를 사용하여 JSON 형식으로 응답
//        return new ResponseEntity<>(jsonData, HttpStatus.OK);