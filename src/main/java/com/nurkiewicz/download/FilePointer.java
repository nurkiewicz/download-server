package com.nurkiewicz.download;

import com.google.common.net.MediaType;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

public interface FilePointer {

	InputStream open();

	long getSize();

	String getOriginalName();

	String getEtag();

	Optional<MediaType> getMediaType();

	boolean matchesEtag(String requestEtag);

	Instant getLastModified();

	boolean modifiedAfter(Instant isModifiedSince);
}
