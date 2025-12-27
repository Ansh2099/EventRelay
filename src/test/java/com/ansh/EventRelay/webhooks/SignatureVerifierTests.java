package com.ansh.EventRelay.webhooks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTests {
	@Test
	void validatesCorrectHmacSha256Signature() throws Exception {
		SignatureVerifier verifier = new SignatureVerifier();

		String secret = "test-secret";
		byte[] body = "{\"id\":\"evt_123\"}".getBytes(StandardCharsets.UTF_8);

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = Base64.getEncoder().encodeToString(mac.doFinal(body));

		assertTrue(verifier.isValid(body, secret, signature));
		assertFalse(verifier.isValid(body, secret, signature + "broken"));
	}
}
