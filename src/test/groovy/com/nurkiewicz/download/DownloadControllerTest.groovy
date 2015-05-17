package com.nurkiewicz.download

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebAppConfiguration
@ContextConfiguration(classes = [MainApplication])
@ActiveProfiles("test")
class DownloadControllerSpec extends Specification {

	private MockMvc mvc

	@Autowired
	public void setWebApplicationContext(WebApplicationContext wac) {
		mvc = MockMvcBuilders.webAppContextSetup(wac).build()
	}

	def 'should return bytes of existing file'() {
		expect:
			mvc
					.perform(get('/download/' + FileStorageStub.TXT_FILE))
					.andExpect(status().isOk())
					.andExpect(content().string("foobar"))
	}

	def 'should return 404 and no content'() {
		expect:
			mvc
					.perform(get('/download/' + FileStorageStub.NOT_FOUND))
					.andExpect(status().isNotFound())
					.andExpect(content().bytes(new byte[0]))
	}
}
