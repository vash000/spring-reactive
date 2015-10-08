/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.reactive.web.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.Publishers;
import reactor.Subscribers;
import reactor.fn.Supplier;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.reactive.web.http.HttpHandler;
import org.springframework.reactive.web.http.ServerHttpRequest;
import org.springframework.reactive.web.http.ServerHttpResponse;

/**
 * Central dispatcher for HTTP request handlers/controllers. Dispatches to registered
 * handlers for processing a web request, providing convenient mapping facilities.
 *
 * <li>It can use any {@link HandlerMapping} implementation to control the routing of
 * requests to handler objects. HandlerMapping objects can be defined as beans in
 * the application context.
 *
 * <li>It can use any {@link HandlerAdapter}; this allows for using any handler interface.
 * HandlerAdapter objects can be added as beans in the application context.
 *
 * <li>It can use any {@link HandlerResultHandler}; this allows to process the result of
 * the request handling. HandlerResultHandler objects can be added as beans in the
 * application context.
 *
 * @author Rossen Stoyanchev
 * @author Sebastien Deleuze
 */
public class DispatcherHandler implements HttpHandler, ApplicationContextAware {

	private static final Log logger = LogFactory.getLog(DispatcherHandler.class);


	private List<HandlerMapping> handlerMappings;

	private List<HandlerAdapter> handlerAdapters;

	private List<HandlerResultHandler> resultHandlers;


	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		initStrategies(applicationContext);
	}

	protected void initStrategies(ApplicationContext context) {

		Map<String, HandlerMapping> mappingBeans =
				BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerMapping.class, true, false);

		this.handlerMappings = new ArrayList<>(mappingBeans.values());
		AnnotationAwareOrderComparator.sort(this.handlerMappings);

		Map<String, HandlerAdapter> adapterBeans =
				BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerAdapter.class, true, false);

		this.handlerAdapters = new ArrayList<>(adapterBeans.values());
		AnnotationAwareOrderComparator.sort(this.handlerAdapters);

		Map<String, HandlerResultHandler> beans =
				BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerResultHandler.class, true, false);

		this.resultHandlers = new ArrayList<>(beans.values());
		AnnotationAwareOrderComparator.sort(this.resultHandlers);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Publisher<Void> handle(ServerHttpRequest request,
			ServerHttpResponse response) {

		if (logger.isDebugEnabled()) {
			logger.debug("Processing " + request.getMethod() + " request for [" + request.getURI() + "]");
		}

		Object handler = getHandler(request);
		if (handler == null) {
			// No exception handling mechanism yet
			response.setStatusCode(HttpStatus.NOT_FOUND);
			return Publishers.empty();
		}

		HandlerAdapter handlerAdapter = getHandlerAdapter(handler);

		return s -> {
			Publisher<HandlerResult> result;
			try {
				result = handlerAdapter.handle(request, response, handler);

				if (Supplier.class.isAssignableFrom(result.getClass())) {
					routeHandlerResult(((Supplier<HandlerResult>) result).get(), request,
							response, s);
				}
				else {
					result.subscribe(Subscribers.create(subscription -> {
								subscription.request(1);
								return s;
							}, (_result, sub) -> {
								sub.cancel();
								routeHandlerResult(_result, request, response, s);
							},
							(error, sub) -> Publishers.<Void>error(error).subscribe(sub),
							Subscriber::onComplete));
				}
			}
			catch (Throwable ex) {
				Publishers.<Void>error(ex).subscribe(s);
			}
		};


	}

	protected void routeHandlerResult(HandlerResult result, ServerHttpRequest request,
			ServerHttpResponse response, Subscriber<? super Void> s) {
		for (HandlerResultHandler resultHandler : resultHandlers) {
			if (resultHandler.supports(result)) {
				resultHandler.handleResult(request, response, result).subscribe(s);
				return;
			}
		}
		Publishers.<Void>error(new IllegalStateException(
				"No HandlerResultHandler for " + result.getValue())).subscribe(s);
	}

	protected Object getHandler(ServerHttpRequest request) {
		Object handler = null;
		for (HandlerMapping handlerMapping : this.handlerMappings) {
			handler = handlerMapping.getHandler(request);
			if (handler != null) {
				break;
			}
		}
		return handler;
	}

	protected HandlerAdapter getHandlerAdapter(Object handler) {
		for (HandlerAdapter handlerAdapter : this.handlerAdapters) {
			if (handlerAdapter.supports(handler)) {
				return handlerAdapter;
			}
		}
		// more specific exception
		throw new IllegalStateException("No HandlerAdapter for " + handler);
	}

}
