package com.ansh.EventRelay.webhooks;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "eventrelay.webhook")
public class WebhookSecretsProperties {
	private Map<String, String> secrets = new HashMap<>();

	public Map<String, String> getSecrets() {
		return secrets;
	}

	public void setSecrets(Map<String, String> secrets) {
		this.secrets = secrets;
	}

	public String getSecretForSource(String source) {
		return secrets.get(source);
	}
}
