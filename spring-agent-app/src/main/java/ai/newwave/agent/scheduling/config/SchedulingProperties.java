package ai.newwave.agent.scheduling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.scheduling")
public class SchedulingProperties {

    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
