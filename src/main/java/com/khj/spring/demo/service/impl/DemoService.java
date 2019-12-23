package com.khj.spring.demo.service.impl;

import com.khj.spring.demo.service.IDemoService;
import com.khj.spring.mvcframework.servlet.annotation.GPService;

/**
 * 核心业务逻辑
 */
@GPService
public class DemoService implements IDemoService {

	public String get(String name) {
		return "My name is " + name;
	}

}
