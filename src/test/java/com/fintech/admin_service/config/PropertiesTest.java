package com.fintech.admin_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PropertiesTest {

    @Test
    void healthTargetsParse() {
        var props = new AdminProperties(new AdminProperties.Downstream("u", "b", "f", Duration.ofSeconds(1)),
                "a=http://x:1, b=http://y:2,,bad");
        assertThat(props.healthTargetMap()).containsEntry("a", "http://x:1").containsEntry("b", "http://y:2").hasSize(2);
    }
}
