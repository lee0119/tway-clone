package com.example.intermediate.service;

import com.example.intermediate.domain.Member;
import com.example.intermediate.dto.request.LoginRequestDto;
import com.example.intermediate.dto.request.MemberRequestDto;
import com.example.intermediate.dto.response.ResponseDto;
import com.example.intermediate.dto.request.TokenDto;
import com.example.intermediate.jwt.TokenProvider;
import com.example.intermediate.repository.MemberRepository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class MemberService {

  private final MemberRepository memberRepository;

  private final PasswordEncoder passwordEncoder;
  private final TokenProvider tokenProvider;
  @Value("${admin.token}")
  String ADMIN_TOKEN;

  @Value("${restAPI.key}")
  String apiKey;

  @Transactional
  public ResponseDto<?> createMember(MemberRequestDto requestDto) {
    if (null != isPresentMember(requestDto.getUserId())) {  //아이디 중복여부 확인
      return ResponseDto.fail("DUPLICATED_USER_ID",
              "중복된 아이디 입니다.");
    }

    if (!requestDto.getPassword().equals(requestDto.getPasswordConfirm())) {  //비밀번호 일치여부 확인
      return ResponseDto.fail("PASSWORDS_NOT_MATCHED",
              "비밀번호와 비밀번호 확인이 일치하지 않습니다.");
    }

    Member member = Member.builder()  //유저 생성
            .userId(requestDto.getUserId())
            .password(passwordEncoder.encode(requestDto.getPassword()))
            .build();
    memberRepository.save(member);

    return ResponseDto.success("회원가입에 성공했습니다");
  }

  @Transactional
  public ResponseDto<?> login(LoginRequestDto requestDto, HttpServletResponse response) { //로그인, 기존 등록된 유저인지 확인
    Member member = isPresentMember(requestDto.getUserId());
    if (null == member) {
      return ResponseDto.fail("MEMBER_NOT_FOUND",
              "사용자를 찾을 수 없습니다.");
    }

    if (!member.valipassengerPassword(passwordEncoder, requestDto.getPassword())) {  //비밀번호 확인
      return ResponseDto.fail("INVALID_MEMBER", "사용자를 찾을 수 없습니다.");
    }

    TokenDto tokenDto = tokenProvider.generateTokenDto(member); //정상 로그인, 토큰 생성
    tokenToHeaders(tokenDto, response);

    return ResponseDto.success(member.getUserId() + " 로그인에 성공했습니다");
  }

  public ResponseDto<?> logout(HttpServletRequest request) {  //로그아웃, refreshToken 유효성 검사
    if (!tokenProvider.valipassengerToken(request.getHeader("RefreshToken"))) {
      return ResponseDto.fail("INVALID_TOKEN", "Token이 유효하지 않습니다.");
    }
    Member member = tokenProvider.getMemberFromAuthentication();
    if (null == member) {
      return ResponseDto.fail("MEMBER_NOT_FOUND",
              "사용자를 찾을 수 없습니다.");
    }

    return tokenProvider.deleteRefreshToken(member);  //정상 로그아웃, refreshToken 삭제
  }

  @Transactional(readOnly = true)
  public Member isPresentMember(String username) {
    Optional<Member> optionalMember = memberRepository.findByUserId(username);
    return optionalMember.orElse(null);
  }
  public void tokenToHeaders(TokenDto tokenDto, HttpServletResponse response) { //accessToken, refreshToken, 유효기간 헤더 추가
    response.addHeader("Authorization", "Bearer " + tokenDto.getAccessToken());
    response.addHeader("RefreshToken", tokenDto.getRefreshToken());
    response.addHeader("Access-Token-Expire-Time", tokenDto.getAccessTokenExpiresIn().toString());
  }

  //kakao 토큰 발급
  public ResponseDto<?> getKakaoAccessToken(String code, HttpServletResponse response) {
    String access_Token = "";
    String refresh_Token = "";
    String reqURL = "https://kauth.kakao.com/oauth/token";

    try {
      URL url = new URL(reqURL);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      //POST 요청을 위해 기본값이 false인 setDoOutput을 true로
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);

      //POST 요청에 필요로 요구하는 파라미터 스트림을 통해 전송
      BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
      StringBuilder sb = new StringBuilder();
      sb.append("grant_type=authorization_code");
      sb.append("&client_id=" + apiKey); // TODO REST_API_KEY 입력
      sb.append("&redirect_uri=http://localhost:3000/api/member/kakao/callback"); // TODO 인가코드 받은 redirect_uri 입력
      sb.append("&code=" + code);
      bw.write(sb.toString());
      bw.flush();

      BufferedReader br;
      //결과 코드가 200이라면 성공 아니면 Error
      int responseCode = conn.getResponseCode();
      if (responseCode != 200)
        br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
      else
        br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

      //요청을 통해 얻은 JSON타입의 Response 메세지 읽어오기
      String line = "";
      String result = "";
      while ((line = br.readLine()) != null) {
        result += line;
      }
      JSONParser parser = new JSONParser();
      JSONObject jsonObject = (JSONObject) parser.parse(result);


      access_Token = jsonObject.get("access_token").toString();
      refresh_Token = jsonObject.get("refresh_token").toString();

      bw.close();
      br.close();
    } catch (IOException | ParseException e) {
      e.printStackTrace();
    }
    return getKakaoUser(access_Token, refresh_Token, response);
  }

  //토큰으로 유저 정보 조회 및 프로젝트에 맞는 형식의 토큰으로 재발급
  public ResponseDto<?> getKakaoUser(String access_token,String refresh_token, HttpServletResponse response) {

    String reqURL = "https://kapi.kakao.com/v2/user/me";

    //access_token을 이용하여 사용자 정보 조회
    try {
      URL url = new URL(reqURL);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Authorization", "Bearer " + access_token); //전송할 header 작성, access_token전송

      //요청을 통해 얻은 JSON타입의 Response 메세지 읽어오기
      BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
      String line = "";
      String result = "";

      while ((line = br.readLine()) != null) {
        result += line;
      }

      JSONParser parser = new JSONParser();
      JSONObject jsonObject = (JSONObject) parser.parse(result);
      String kakaoId = jsonObject.get("id").toString();
      JSONObject kakao_account = (JSONObject)jsonObject.get("kakao_account");
      String email = kakao_account.get("email").toString();

      Member member = memberRepository.findByUserId(email).orElse(null);

      //이미 가입된 회원이 아니라면 회원가입 자동으로 진행
      if(null == member) {
        String password = kakaoId + ADMIN_TOKEN;
        String encodedPassword = passwordEncoder.encode(password);

        member = Member.builder()
                .userId(email)
                .password(encodedPassword)
                .build();

        memberRepository.save(member);

      }

      //토큰 생성
      TokenDto tokenDto = tokenProvider.kakaoTokenDto(refresh_token,member);
      tokenToHeaders(tokenDto, response);
      br.close();

      return ResponseDto.success(member.getUserId() + " 로그인에 성공했습니다");

    } catch (IOException | ParseException e) {
      return ResponseDto.fail("FAIL_LOGIN", "로그인에 실패하였습니다");
    }
  }
}
