package org.voovan.http.message;

import org.voovan.http.message.packet.*;
import org.voovan.http.server.context.WebContext;
import org.voovan.network.IoSession;
import org.voovan.tools.TByteBuffer;
import org.voovan.tools.TString;
import org.voovan.tools.exception.MemoryReleasedException;
import org.voovan.tools.log.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 响应对象
 *
 * @author helyho
 *
 * Voovan Framework.
 * WebSite: https://github.com/helyho/Voovan
 * Licence: Apache v2 License
 */
public class Response {
	private ResponseProtocol 	protocol;
	private Header				header;
	private List<Cookie>		cookies;
	private Body 				body;
	private boolean				isCompress;
	protected boolean 			basicSend = false;

	/**
	 * 构造函数
	 *
	 * @param response 响应对象
	 */
	protected Response(Response response) {
		this.protocol = response.protocol;
		this.header = response.header;
		this.body = response.body;
		this.cookies = response.cookies;
		this.isCompress = response.isCompress;
	}

	/**
	 * 构造函数
	 */
	public Response() {
		protocol = new ResponseProtocol();
		header = new Header();
		cookies = new ArrayList<Cookie>();
		body = new Body();
		isCompress = false;
	}

	/**
	 * 是否压缩 默认为 true
	 *
	 * @return 是否启用个压缩
	 */
	public boolean isCompress() {
		return isCompress;
	}

	/**
	 * 设置压缩属性
	 *
	 * @param isCompress 是否启用个压缩
	 */
	public void setCompress(boolean isCompress) {
		this.isCompress = isCompress;
	}

	/**
	 * 获取协议对象
	 *
	 * @return 返回响应协议对象
	 */
	public ResponseProtocol protocol() {
		return protocol;
	}

	/**
	 * 获取 Header 对象
	 *
	 * @return HTTP-Header 对象
	 */
	public Header header() {
		return header;
	}

	/**
	 * 获取所有的Cookies对象,返回一个 List
	 *
	 * @return Cookie 对象集合
	 */
	public List<Cookie> cookies() {
		return cookies;
	}

	/**
	 * 获取 Body 对象
	 *
	 * @return Body 对象
	 */
	public Body body() {
		return body;
	}

	/**
	 * 根据内容构造一写必要的 Header 属性
	 */
	private void initHeader() {
		// 根据压缩属性确定 Header 的一些属性内容
		if (body.size()!=0 && isCompress) {
			header.put("Transfer-Encoding", "chunked");
			header.put("Content-Encoding", "gzip");
		} else {
			header.put("Content-Length", Integer.toString((int)body.size()));
		}

		if (TString.isNullOrEmpty(header.get("Content-Type"))) {
			header.put("Content-Type", "text/html");
		}

		header.put("Content-Type", TString.assembly(header.get("Content-Type"), ";charset=", WebContext.getWebServerConfig().getCharacterSet()));
	}

	/**
	 * 根据 Cookie 对象,生成 HTTP 响应中的 Cookie 字符串 用于报文拼装
	 *
	 * @return Cookie 字符串
	 */
	private String genCookie() {
		StringBuilder cookieString = new StringBuilder();
		for (Cookie cookie : cookies) {
			cookieString.append("Set-Cookie: ");
			cookieString.append(cookie.toString());
			cookieString.append("\r\n");
		}
		return cookieString.toString();
	}



	/**
	 * 根据对象的内容,构造 Http 响应报头
	 *
	 * @return ByteBuffer 响应报文的报头
	 */
	private ByteBuffer readHead() {

		StringBuilder stringBuilder = new StringBuilder();

		initHeader();

		stringBuilder.append(protocol.toString());


		stringBuilder.append(header.toString());


		stringBuilder.append(genCookie());


		stringBuilder.append("\r\n");


		try {
			return ByteBuffer.wrap(stringBuilder.toString().getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			Logger.error("Response.readHead io error",e);
			return null;
		}
	}

	private ByteBuffer readEnd(){
		if (isCompress) {
			return ByteBuffer.wrap("0\r\n\r\n".getBytes());
		}else{
			return TByteBuffer.EMPTY_BYTE_BUFFER;
		}
	}

	/**
	 * 发送数据
	 * @param session socket 会话对象
	 * @throws IOException IO异常
	 */
	public void send(IoSession session) throws IOException {

		//发送报文头
		ByteBuffer byteBuffer = null;

		try {
			byteBuffer = readHead();
		} catch (Throwable e){
			if(!(e instanceof MemoryReleasedException)){
				Logger.error("Response send error: ", (Exception) e);
			}
		}

		if(byteBuffer == null){
			return;
		}

		session.send(byteBuffer);

		//是否需要压缩
		if(isCompress){
			body.compress();
		}

		//发送报文主体
		if(body.size() != 0) {

			//准备缓冲区
			byteBuffer = TByteBuffer.allocateDirect();
			int readSize = 0;
			try{
				while (true) {

					readSize = body.read(byteBuffer);

					if (readSize == -1) {
						break;
					}

					//判断是否需要发送 chunked 段长度
					if (isCompress() && readSize!=0) {
						String chunkedLengthLine = Integer.toHexString(readSize) + "\r\n";
						session.send(ByteBuffer.wrap(chunkedLengthLine.getBytes()));
					}

					session.send(byteBuffer);
					byteBuffer.clear();

					//判断是否需要发送 chunked 结束符号
					if (isCompress() && readSize!=0) {
						session.send(ByteBuffer.wrap("\r\n".getBytes()));
					}
				}
			} catch (Throwable e){
				if(!(e instanceof MemoryReleasedException)){
					Logger.error("Response send error: ", (Exception) e);
				}
				return;
			} finally {
				//发送报文结束符
				session.send(readEnd());
				TByteBuffer.release(byteBuffer);
				release();
			}
		}

		basicSend = true;
	}

	public void release(){
		body.release();
	}

	/**
	 * 清理
	 */
	public void clear(){
		this.header().clear();
		this.cookies().clear();
		this.protocol().clear();
		this.body().clear();
	}

	@Override
	public String toString() {
		return new String(TByteBuffer.toString(readHead()));
	}
}
