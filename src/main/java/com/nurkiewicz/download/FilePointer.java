package com.nurkiewicz.download;

import com.google.common.hash.HashCode;
import com.google.common.net.MediaType;

import java.io.File;
import java.io.InputStream;
import java.util.Optional;

public interface FilePointer {

	InputStream open();

	long getSize();

	String getOriginalName();

	HashCode getTag();

	Optional<MediaType> getMediaType();

}
