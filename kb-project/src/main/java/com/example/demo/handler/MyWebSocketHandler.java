package com.example.demo.handler;

import java.net.InetSocketAddress;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.demo.service.BankAccountService;
import com.example.demo.service.BookMarkService;
import com.example.demo.service.GPTChatRestService;
import com.example.demo.service.LogService;
import com.example.demo.service.UserService;
import com.example.demo.dto.GPTResponseDto;
import com.example.demo.entity.BankAccount;
import com.example.demo.entity.BookMark;
import com.example.demo.entity.User;

@Component
public class MyWebSocketHandler extends TextWebSocketHandler {

	@Autowired
	UserService userService;

	@Autowired
	BankAccountService bankAccountService;

	@Autowired
	LogService logService;

	@Autowired
	BookMarkService bookMarkService;

	@Autowired
	GPTChatRestService gptChatRestService; // chat gpt rest api

	static private HttpServletRequest request;

	static long amount;
	static String name;
	static String action;
	static String clientIp;

	private enum UserState {
		INITIAL, WAITING_CONFIRMATION // 초기 상태 및 확인(예/아니오) 기다리는 상태
	}

	private UserState userState = UserState.INITIAL; // 현재는 초기상태
	private User user = new User(); // HttpSession에서 가져온 user정보를 담을 객체

	
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		System.out.println("연결 시도중");
		userState = UserState.INITIAL; // 현재는 초기상태
		InetSocketAddress clientAddress = session.getRemoteAddress();
		clientIp = clientAddress.getAddress().getHostAddress();
		System.out.println("clientIp: " + clientIp);
		
	}

	
	@Override
	// 실제로 서버와 통신하는
	// handleTextMessage
	public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		String payload = message.getPayload(); // message(Client textMessage), 사용자의 메세지
		user = (User) session.getAttributes().get("user"); // Session으로부터 유저 정보 가져옴
		System.out.println(payload);
		GPTResponseDto gptResponseDto = gptChatRestService.completionChat(payload);
		System.out.println("Current State: " + userState);

		if (gptResponseDto.getAction().equals("송금")) {
			action = "송금";
			amount = gptResponseDto.getAmount().longValue();
			name = gptResponseDto.getName();
		}

		else if (gptResponseDto.getAction().equals("조회")) {
			action = "조회";
		}

		else if (gptResponseDto.getAction().equals("예")) {
			action = "예";
		}

		else if (gptResponseDto.getAction().equals("아니오")) {
			action = "아니오";
		}

		else if (gptResponseDto.getAction().equals("즐겨찾기")) {
			action = "즐겨찾기";
		}

		else
			action = "etc";

		System.out.println("socket received action : " + action);

		if (userState == UserState.INITIAL) {
			System.out.println(clientIp);
			System.out.println(user.getClientSafeIp());

			if (action.equals("송금")) {
//				if (bookMarkService.findByUserAndBookMarkName(user, name) == null) {
				if (bookMarkService.findByUserAndname(user, name) == null) {
					session.sendMessage(new TextMessage(name + "은 즐겨찾기에 존재하지 않는 사용자입니다. 다시 말씀해주세요."));
				} else if (!clientIp.equals(user.getClientSafeIp())) {
					session.sendMessage(new TextMessage("인가되지 않은 사용자의 PC 입니다."));
				} else {
					session.sendMessage(new TextMessage(name + "에게 " + amount + "원 송금하시겠습니까?")); // Client에게 값 전송
					userState = UserState.WAITING_CONFIRMATION;
				}
			}

			else if (action.equals("조회")) {
				List<BankAccount> bankAccountByuserId = bankAccountService.getBankAccountByUser(user);
				Long balance = bankAccountByuserId.get(0).getAmount();
				String username = user.getUsername();
				String msg = username + "의 잔액은 " + balance.toString() + "원 입니다.";
				session.sendMessage(new TextMessage(msg));
				name = ""; // 초기화
				amount = 0L; // name amount는 static이기 때문에 계속 초기화해줘야한다.
			}

			else if (action.equals("즐겨찾기")) {

				List<BookMark> bookMarkUsers = bookMarkService.getAllBookMarkUserName(user);
				String msg = "현재 즐겨찾기에 추가된 사용자는 ";
				for (BookMark bookMarkUser : bookMarkUsers) {
//					if(bookMarkService.findByUserAndBookMarkName(user, bookMarkUser.getBookMarkName()) != null){
					if (bookMarkService.findByUserAndname(user,
							bookMarkUser.getBankAccount().getUser().getUsername()) != null) {
//						msg += " " + bookMarkUser.getBookMarkBankname() + "은행의 " + bookMarkUser.getBookMarkName();
						msg += " " + bookMarkUser.getBankAccount().getBank().getBankname() + "은행의 "
								+ bookMarkUser.getBankAccount().getUser().getUsername();
					}
				}
				
				msg += " 입니다.";
				session.sendMessage(new TextMessage(msg));
				name = ""; // 초기화
				amount = 0L; // name amount는 static이기 때문에 계속 초기화해줘야한다.
			}

			else {
				session.sendMessage(new TextMessage("다시 말씀해주세요."));
				name = "";
				amount = 0L;
			}
		}

		else if (userState == UserState.WAITING_CONFIRMATION) { // 상태가 예, 아니오로 바뀌었을 떄, (송금용 예/아니오)
			if (action.equals("예")) {
				BookMark bookMarkUser = bookMarkService.findByUserAndname(user, name);

				bankAccountService.transferToBookMarkUser(bookMarkUser, user, amount);
				session.sendMessage(new TextMessage("송금이 완료되었습니다."));
				List<BankAccount> bankAccountByuserId = bankAccountService.getBankAccountByUser(user);
				Long balance = bankAccountByuserId.get(0).getAmount();
				String username = user.getUsername();
				String msg = username + "의 잔액은 " + balance.toString() + "원 입니다.";
				session.sendMessage(new TextMessage(msg));
				userState = UserState.INITIAL;
			} else if (action.equals("아니오")) {
				session.sendMessage(new TextMessage("다시 말씀해주세요."));
				userState = UserState.INITIAL;
			} else {
				session.sendMessage(new TextMessage("송금을 취소하였습니다."));
				userState = UserState.INITIAL;
			}
			name = "";
			amount = 0L;
			
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
		// 클라이언트와의 연결이 종료되었을 때 실행되는 메소드
		System.out.println("WebSocket connection closed.");
	}
	
}