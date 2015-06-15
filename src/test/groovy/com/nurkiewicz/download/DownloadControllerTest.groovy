package com.nurkiewicz.download

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

import static com.google.common.net.HttpHeaders.*
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebAppConfiguration
@ContextConfiguration(classes = [MainApplication])
@ActiveProfiles("test")
class DownloadControllerSpec extends Specification {

	private MockMvc mockMvc

	private static final String TEXT_FILE = '/download/' + FileExamples.TXT_FILE_UUID + '/file.txt';

	@Autowired
	public void setWebApplicationContext(WebApplicationContext wac) {
		mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
	}

	def 'should return bytes of existing file'() {
		expect:
			mockMvc
					.perform(
						get(TEXT_FILE))
					.andExpect(status().isOk())
					.andExpect(content().string("foobar"))
	}

	def 'should return 404 and no content'() {
		expect:
			mockMvc
					.perform(
						get('/download/' + FileExamples.NOT_FOUND_UUID + "/file.txt"))
					.andExpect(status().isNotFound())
					.andExpect(content().bytes(new byte[0]))
	}

	def 'should send file if ETag not present'() {
		expect:
			mockMvc
					.perform(
						get(TEXT_FILE))
					.andExpect(
						status().isOk())
		}

	def 'should send file if ETag present but not matching'() {
		expect:
			mockMvc
					.perform(
						get(TEXT_FILE)
								.header(IF_NONE_MATCH, '"WHATEVER"'))
					.andExpect(
						status().isOk())
	}

	def 'should not send file if ETag matches content'() {
		given:
			String etag = FileExamples.TXT_FILE.getEtag()
		expect:
			mockMvc
					.perform(
						get(TEXT_FILE)
								.header(IF_NONE_MATCH, etag))
					.andExpect(
						status().isNotModified())
					.andExpect(
						header().string(ETAG, etag))
	}

	def 'should not return file if wasn\'t modified recently'() {
		given:
			Instant lastModified = FileExamples.TXT_FILE.getLastModified()
			String dateHeader = toDateHeader(lastModified)
		expect:
			mockMvc
					.perform(
					get(TEXT_FILE)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isNotModified())
	}

	def 'should not return file if server has older version than the client'() {
		given:
			Instant lastModifiedLaterThanServer = FileExamples.TXT_FILE.getLastModified().plusSeconds(60)
			String dateHeader = toDateHeader(lastModifiedLaterThanServer)
		expect:
			mockMvc
					.perform(
					get(TEXT_FILE)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isNotModified())
	}

	def 'should return file if was modified after last retrieval'() {
		given:
			Instant lastModifiedRecently = FileExamples.TXT_FILE.getLastModified().minusSeconds(60)
			String dateHeader = toDateHeader(lastModifiedRecently)
		expect:
			mockMvc
					.perform(
					get(TEXT_FILE)
							.header(IF_MODIFIED_SINCE, dateHeader))
					.andExpect(
							status().isOk())
	}

	def 'should return 200 OK on HEAD request, but without body'() {
		expect:
			mockMvc
					.perform(
						head(TEXT_FILE))
					.andExpect(
							status().isOk())
					.andExpect(
							content().bytes(new byte[0]))
	}

	def 'should return 304 on HEAD request if we have cached version'() {
		expect:
			mockMvc
				.perform(
					head(TEXT_FILE)
							.header(IF_NONE_MATCH, FileExamples.TXT_FILE.getEtag()))
				.andExpect(
					status().isNotModified())
				.andExpect(
					header().string(ETAG, FileExamples.TXT_FILE.getEtag()))
	}

	def 'should return Content-length header'() {
		expect:
			mockMvc
					.perform(
					head(TEXT_FILE))
					.andExpect(
					status().isOk())
					.andExpect(
					header().longValue(CONTENT_LENGTH, FileExamples.TXT_FILE.size))
	}

	def 'should return content type in response'() {
		expect:
			mockMvc
					.perform(
					head(TEXT_FILE))
					.andExpect(
					status().isOk())
					.andExpect(
					header().string(CONTENT_TYPE, "text/plain"))
	}

	private static String toDateHeader(Instant lastModified) {
		ZonedDateTime dateTime = ZonedDateTime.ofInstant(lastModified, ZoneOffset.UTC)
		DateTimeFormatter.RFC_1123_DATE_TIME.format(dateTime)
	}

}
