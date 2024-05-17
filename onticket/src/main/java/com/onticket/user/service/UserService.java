package com.onticket.user.service;
import com.onticket.user.component.CoolSmsApi;
import com.onticket.user.domain.SiteUser;
import com.onticket.user.form.UserCreateForm;
import com.onticket.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import net.nurigo.sdk.NurigoApp;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
import net.nurigo.sdk.message.response.SingleMessageSentResponse;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Random;


@RequiredArgsConstructor
@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CoolSmsApi coolSmsApi;

    DefaultMessageService messageService;
    //인증번호
    private String smscode;
    //인증번호 발신자
    private String sender="01068549901";

    //ID 중복검사
    public Boolean IsExist(String username) {
       return userRepository.existsByUsername(username);
    }

    //인증번호 생성
    public String GenerateSMSCode(){
        Random rand = new Random();
        String smsmessage;

        String certnumber = "";
        //6자리 인증번호 생성
        for(int i=0; i<6; i++) {
            String ran = Integer.toString(rand.nextInt(10));
            certnumber += ran;
        }

        smscode=certnumber;
        smsmessage="인증번호는 ["+certnumber+"] 입니다.";
        return smsmessage;
    }

    //sms 보내기
    public String SendSMSCode(String to) {
        //인증코드 생성
        String smsmessage=GenerateSMSCode();
        //ApiKey
        messageService = NurigoApp.INSTANCE.initialize(coolSmsApi.getCoolsmsApiKey(), coolSmsApi.getCoolsmsApiSecret(), "https://api.coolsms.co.kr");
        Message message = new Message();
        //발신번호
        message.setFrom(sender);
        //보낼번호
        message.setTo(to);
        //보낼 메세지
        message.setText(smsmessage);
        //메세지 보내기
        SingleMessageSentResponse response = this.messageService.sendOne(new SingleMessageSendingRequest(message));
        //System.out.println(response);
        return smscode;
    }

    //sms 코드 반환
    public String GetSMSCode(){
        return smscode;
    }

    //비밀번호변경
    @Transactional
    public void ChangePassword(String phoneNumber,String password){
        SiteUser siteUser= userRepository.findSiteUserByPhonenumber(phoneNumber);
        String encodedPassword = passwordEncoder.encode(password);
        siteUser.setPassword(encodedPassword);
        userRepository.save(siteUser);
    }

    //유저생성
    @Transactional
    public SiteUser Create(@NotNull UserCreateForm userCreateForm) {
        String userid = userCreateForm.getUsername();
        String email = userCreateForm.getEmail();
        String password = userCreateForm.getPassword1();
        String nickname= userCreateForm.getNickname();
        String number = userCreateForm.getPhonenumber();
        // 패스워드 암호화
        String encodedPassword = passwordEncoder.encode(password);

        // SiteUser 객체 생성
        SiteUser user = new SiteUser();
        user.setUsername(userid);
        user.setEmail(email);
        user.setPassword(encodedPassword);
        user.setNickname(nickname);
        user.setPhonenumber(number);;

        // UserRepository를 사용하여 사용자 정보를 저장
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 사용자가 이미 존재하는 경우에 대한 처리
            throw new IllegalArgumentException("User already exists.");
        }
        return user;
    }

    //유저삭제
    public void Delete (SiteUser user) {
        userRepository.delete(user);
    }
}