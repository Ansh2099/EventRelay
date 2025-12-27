package com.ansh.EventRelay;

import com.ansh.EventRelay.events.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class EventRelayApplicationTests {
	@MockBean
	WebhookEventRepository webhookEventRepository;

	@Test
	void contextLoads() {
	}

}
