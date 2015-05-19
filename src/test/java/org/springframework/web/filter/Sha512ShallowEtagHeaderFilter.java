package org.springframework.web.filter;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.io.InputStream;

public class Sha512ShallowEtagHeaderFilter extends ShallowEtagHeaderFilter {

	@Override
	protected String generateETagHeaderValue(InputStream bytes) {
		try {
			final String hash = DigestUtils.sha256Hex(bytes);
			return "\"" + hash + "\"";
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
