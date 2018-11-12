/*
 * Copyright 2013-2017 the original author or authors.
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
 *
 */

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 * @author Spencer Gibb
 */
// 将RouteDefinition转化为Route
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	// 为了使用GatewayProperties中默认的defaultFilters
	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
									   List<RoutePredicateFactory> predicates,
									   List<GatewayFilterFactory> gatewayFilterFactories,
									   GatewayProperties gatewayProperties) {
		// 设置RouteDefinitionLocator
		this.routeDefinitionLocator = routeDefinitionLocator;
		// 初始化RoutePredicateFactory
		initFactories(predicates);
		// 初始化GatewayFilterFactory
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		// 设置GatewayProperties
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	/**
	 * 初始化predicate工厂
	 * @param predicates
	 */
	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			// key为predicate工厂名字
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute) //convertToRoute将RouteDefinition转换为Route
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		// 将Predicate转换成AsyncPredicate，合并Predicate。
		// 这样做的目的：RoutePredicateHandlerMapping为请求匹配Route，只要调用一次Predicate#test(ServerWebExchange)方法。
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		// 将FilterDefinition转换成GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		return Route.async(routeDefinition)
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		List<GatewayFilter> filters = filterDefinitions.stream()
				.map(definition -> {
					// 获取FilterDefinition对应的GatewayFilter工厂
					GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
					if (factory == null) {
                        throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
					}
					// 获取FilterDefinition中的参数
					Map<String, String> args = definition.getArgs();
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
					}
					// 将map参数做转换，key为config类的属性名称
                    Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
					// 调用factory的newConfig方法创建一个config类对象
                    Object configuration = factory.newConfig();
                    // 将转换的参数绑定到config
                    ConfigurationUtils.bind(configuration, properties,
                            factory.shortcutFieldPrefix(), definition.getName(), validator);
					// 调用factory的apply方法生成GatewayFilter
                    GatewayFilter gatewayFilter = factory.apply(configuration);
                    if (this.publisher != null) {
                        this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
                    }
                    return gatewayFilter;
				})
				.collect(Collectors.toList());
		// 将所有的Filter转换成的GatewayFilter定义为有序
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			GatewayFilter gatewayFilter = filters.get(i);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	/**
	 * 将默认的filter应用于route的filter中，并根据order排序
	 * @param routeDefinition
	 * @return
	 */
	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//TODO: support option to apply defaults after route specific filters?
		// 将GatewayProperties中定义的默认FilterDefinition转换成GatewayFilter
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters("defaultFilters",
					this.gatewayProperties.getDefaultFilters()));
		}

		// 将RouteDefinition中定义的FilterDefinition转换成GatewayFilter
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		//将列表中第一个PredicateDefinition转换成AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			// 将列表中每一个PredicateDefinition转换成AsyncPredicate
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			// 应用and操作，将所有AsyncPredicate组合成一个AsyncPredicate
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		// 根据predicate名称获取对应的RoutePredicateFactory
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}
		// 获取PredicateDefinition中的Map类型参数，key的形式是:_genkey_+数字
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ args + " to " + predicate.getName());
		}

		// 将map参数做转换，key为config类的属性名称
        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
		// 调用factory的newConfig方法创建一个config类对象
		Object config = factory.newConfig();
		// 将转换后的参数绑定到config对象上
        ConfigurationUtils.bind(config, properties,
                factory.shortcutFieldPrefix(), predicate.getName(), validator);
        if (this.publisher != null) {
            this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
        }
        // 将config作为参数代入，调用factory的applyAsync方法创建AsyncPredicate对象
        return factory.applyAsync(config);
	}
}
