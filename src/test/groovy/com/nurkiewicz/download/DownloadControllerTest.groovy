package com.nurkiewicz.download

import groovy.transform.CompileStatic
import org.apache.commons.configuration.BaseConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebAppConfiguration
@ContextConfiguration(classes = [MainApplication])
class DownloadControllerSpec extends Specification {

	@Autowired
	private WebApplicationContext webApplicationContext;

	def 'smoke'() {
		given:
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
		expect:
			mvc
					.perform(get('/download/' + UUID.randomUUID().toString()))
					.andExpect(status().isOk())
					.andExpect(content().string("foobar"))
	}
}
