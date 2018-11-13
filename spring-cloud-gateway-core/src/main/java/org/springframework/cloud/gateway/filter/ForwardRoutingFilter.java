package org.springframework.cloud.gateway.filter;

import java.net.URI;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

public class ForwardRoutingFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(ForwardRoutingFilter.class);

	private final ObjectProvider<DispatcherHandler> dispatcherHandler;

	public ForwardRoutingFilter(ObjectProvider<DispatcherHandler> dispatcherHandler) {
		this.dispatcherHandler = dispatcherHandler;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		// 判断ForwardRoutingFilter能否处理该请求
		String scheme = requestUrl.getScheme();
		// 满足两个条件：1.forward:// 前缀( Scheme ) 2.未被路由
		if (isAlreadyRouted(exchange) || !"forward".equals(scheme)) {
			return chain.filter(exchange);
		}
		// 设置已经路由
		setAlreadyRouted(exchange);

		//TODO: translate url?

		if (log.isTraceEnabled()) {
			log.trace("Forwarding to URI: "+requestUrl);
		}

		// DispatcherHandler匹配并转发到当前网关实例本地接口
		return this.dispatcherHandler.getIfAvailable().handle(exchange);
	}
}
