/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.server.reactive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import org.reactivestreams.Publisher;
import reactor.core.converter.RxJava1ObservableConverter;
import reactor.core.publisher.Mono;
import rx.Observable;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.util.Assert;
import rx.functions.Func1;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adapt {@link ServerHttpResponse} to the RxNetty {@link HttpServerResponse}.
 *
 * @author Rossen Stoyanchev
 * @author Stephane Maldini
 */
public class RxNettyServerHttpResponse extends AbstractServerHttpResponse {

	private final HttpServerResponse<ByteBuf> response;

	public RxNettyServerHttpResponse(HttpServerResponse<ByteBuf> response,
			NettyDataBufferFactory dataBufferFactory) {
		super(dataBufferFactory);
		Assert.notNull("'response', response must not be null.");

		this.response = response;
	}


	public HttpServerResponse<?> getRxNettyResponse() {
		return this.response;
	}

	@Override
	public void setStatusCode(HttpStatus status) {
		this.response.setStatus(HttpResponseStatus.valueOf(status.value()));
	}

	@Override
	protected Mono<Void> writeWithInternal(Publisher<DataBuffer> publisher) {
		return RxJava1ObservableConverter
				.from(response.write(toObservableByteBuf(publisher)))
				.then();
	}

	private static Observable<ByteBuf> toObservableByteBuf(Publisher<DataBuffer> publisher) {
		return RxJava1ObservableConverter
				.from(publisher)
				.map(buffer -> buffer instanceof NettyDataBuffer ?
								((NettyDataBuffer) buffer).getNativeBuffer()
								: Unpooled.wrappedBuffer(buffer.asByteBuffer())
				);
	}

	@Override
	protected void writeHeaders() {
		getHeaders()
				.entrySet()
				.stream()
				.forEach(e -> response.addHeader(e.getKey(), e.getValue()));
	}

	@Override
	protected void writeCookies() {
		getCookies()
				.values()
				.stream()
				.flatMap(Collection::stream)
				.map(RxNettyServerHttpResponse::copyCookie)
				.forEach(response::addCookie);
	}

	private static Cookie copyCookie(ResponseCookie responseCookie) {

		final Cookie cookie = new DefaultCookie(responseCookie.getName(), responseCookie.getValue());
		if (!responseCookie.getMaxAge().isNegative()) {
			cookie.setMaxAge(responseCookie.getMaxAge().getSeconds());
		}
		responseCookie.getDomain().ifPresent(cookie::setDomain);
		responseCookie.getPath().ifPresent(cookie::setPath);
		cookie.setSecure(responseCookie.isSecure());
		cookie.setHttpOnly(responseCookie.isHttpOnly());
		return cookie;


	}

/*
	While the underlying implementation of {@link ZeroCopyHttpOutputMessage} seems to
	work; it does bypass {@link #applyBeforeCommit} and more importantly it doesn't change
	its {@linkplain #state()). Therefore it's commented out, for now.

	We should revisit this code once
	https://github.com/ReactiveX/RxNetty/issues/194 has been fixed.
	

	@Override
	public Mono<Void> writeWith(File file, long position, long count) {
		Channel channel = this.response.unsafeNettyChannel();

		HttpResponse httpResponse =
				new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		io.netty.handler.codec.http.HttpHeaders headers = httpResponse.headers();

		for (Map.Entry<String, List<String>> header : getHeaders().entrySet()) {
			String headerName = header.getKey();
			for (String headerValue : header.getValue()) {
				headers.add(headerName, headerValue);
			}
		}
		Mono<Void> responseWrite = MonoChannelFuture.from(channel.write(httpResponse));

		FileRegion fileRegion = new DefaultFileRegion(file, position, count);
		Mono<Void> fileWrite = MonoChannelFuture.from(channel.writeAndFlush(fileRegion));

		return Flux.concat(applyBeforeCommit(), responseWrite, fileWrite).then();
	}
*/
}