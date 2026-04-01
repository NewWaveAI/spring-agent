package ai.newwave.agent.timeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.timeline")
public class TimelineProperties {

    private boolean enabled = false;
    private int maxStoreSize = 10_000;
    private int maxRecentEventsForContext = 20;
    private boolean contextInjectionEnabled = true;
    private boolean queryToolEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxStoreSize() { return maxStoreSize; }
    public void setMaxStoreSize(int maxStoreSize) { this.maxStoreSize = maxStoreSize; }

    public int getMaxRecentEventsForContext() { return maxRecentEventsForContext; }
    public void setMaxRecentEventsForContext(int v) { this.maxRecentEventsForContext = v; }

    public boolean isContextInjectionEnabled() { return contextInjectionEnabled; }
    public void setContextInjectionEnabled(boolean v) { this.contextInjectionEnabled = v; }

    public boolean isQueryToolEnabled() { return queryToolEnabled; }
    public void setQueryToolEnabled(boolean v) { this.queryToolEnabled = v; }
}
