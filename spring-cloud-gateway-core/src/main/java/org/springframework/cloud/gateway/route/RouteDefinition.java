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

import javax.validation.constraints.NotEmpty;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.ValidationException;
import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * @author Spencer Gibb
 */
// 对Route进行定义，最终会被RouteLocator解析为Route
@Validated
public class RouteDefinition {
	// id，唯一
	@NotEmpty
	private String id = UUID.randomUUID().toString();

	// 谓语定义数组，请求通过predicates是否匹配。在Route里，PredicateDefinition转换成Predicate。单个Predicate中不要出现逗号。
	@NotEmpty
	@Valid
	private List<PredicateDefinition> predicates = new ArrayList<>();

	// 过滤器定义数组，在Route里，FilterDefinition转换成GatewayFilter
	@Valid
	private List<FilterDefinition> filters = new ArrayList<>();

	// 路由的uri
	@NotNull
	private URI uri;

	// 顺序
	private int order = 0;

	public RouteDefinition() {}

	/**
	 * 通过text创建RouteDefinition对象<br/>
	 * 例如 route001=http://127.0.0.1,Host=**.addrequestparameter.org,Path=/get
	 * @param text
	 */
	public RouteDefinition(String text) {
		int eqIdx = text.indexOf('=');
		if (eqIdx <= 0) {
			throw new ValidationException("Unable to parse RouteDefinition text '" + text + "'" +
					", must be of the form name=value");
		}

		setId(text.substring(0, eqIdx));

		String[] args = tokenizeToStringArray(text.substring(eqIdx+1), ",");

		setUri(URI.create(args[0]));

		for (int i=1; i < args.length; i++) {
			this.predicates.add(new PredicateDefinition(args[i]));
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public List<PredicateDefinition> getPredicates() {
		return predicates;
	}

	public void setPredicates(List<PredicateDefinition> predicates) {
		this.predicates = predicates;
	}

	public List<FilterDefinition> getFilters() {
		return filters;
	}

	public void setFilters(List<FilterDefinition> filters) {
		this.filters = filters;
	}

	public URI getUri() {
		return uri;
	}

	public void setUri(URI uri) {
		this.uri = uri;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RouteDefinition routeDefinition = (RouteDefinition) o;
		return Objects.equals(id, routeDefinition.id) &&
				Objects.equals(predicates, routeDefinition.predicates) &&
				Objects.equals(order, routeDefinition.order) &&
				Objects.equals(uri, routeDefinition.uri);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, predicates, uri);
	}

	@Override
	public String toString() {
		return "RouteDefinition{" +
				"id='" + id + '\'' +
				", predicates=" + predicates +
				", filters=" + filters +
				", uri=" + uri +
				", order=" + order +
				'}';
	}
}
