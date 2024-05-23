package com.onticket.concert.batch.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.onticket.concert.batch.dto.StyUrlsDto;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


//스토리Url data 문자열로 들어오기도하고 배열로 들어오기도 해서 deseializer 를 사용해서 처리
public class StyUrlsDeserializer extends JsonDeserializer<StyUrlsDto> {

    @Override
    public StyUrlsDto deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = p.getCodec().readTree(p);
        StyUrlsDto styUrls = new StyUrlsDto();
        List<String> styUrlList = new ArrayList<>();

        if (node.has("styurl")) {
            JsonNode styurlNode = node.get("styurl");
            if (styurlNode.isArray()) {
                for (JsonNode urlNode : styurlNode) {
                    styUrlList.add(urlNode.asText());
                }
            } else if (styurlNode.isTextual()) {
                styUrlList.add(styurlNode.asText());
            }
        }

        styUrls.setStyUrlDto(styUrlList);
        return styUrls;
    }
}
