package com.ansh.EventRelay.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SignatureVerifier {
	private static final String HMAC_ALGO = "HmacSHA256";

	public boolean isValid(byte[] rawBody, String secret, String providedSignatureBase64) {
		if (secret == null || secret.isBlank()) {
			return false;
		}
		if (providedSignatureBase64 == null || providedSignatureBase64.isBlank()) {
			return false;
		}

		byte[] expected = computeHmac(rawBody, secret);
		byte[] provided;
		try {
			provided = Base64.getDecoder().decode(providedSignatureBase64);
		} catch (IllegalArgumentException e) {
			return false;
		}
		return MessageDigest.isEqual(expected, provided);
	}

	private byte[] computeHmac(byte[] data, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
			return mac.doFinal(data);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("Unable to compute HMAC", e);
		}
	}
}
