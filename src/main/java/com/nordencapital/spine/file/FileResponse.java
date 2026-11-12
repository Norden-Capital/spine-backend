package com.nordencapital.spine.file;

import java.util.UUID;

public record FileResponse(UUID id, String name, String contentType, long size) {

	public static FileResponse from(StoredFile file) {
		return new FileResponse(file.getId(), file.getOriginalName(), file.getContentType(), file.getSize());
	}

}
