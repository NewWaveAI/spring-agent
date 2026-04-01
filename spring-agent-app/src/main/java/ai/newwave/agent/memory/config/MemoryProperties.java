package ai.newwave.agent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    private boolean enabled = false;
    private boolean contextInjectionEnabled = true;
    private boolean saveToolEnabled = true;
    private boolean searchToolEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isContextInjectionEnabled() { return contextInjectionEnabled; }
    public void setContextInjectionEnabled(boolean contextInjectionEnabled) { this.contextInjectionEnabled = contextInjectionEnabled; }

    public boolean isSaveToolEnabled() { return saveToolEnabled; }
    public void setSaveToolEnabled(boolean saveToolEnabled) { this.saveToolEnabled = saveToolEnabled; }

    public boolean isSearchToolEnabled() { return searchToolEnabled; }
    public void setSearchToolEnabled(boolean searchToolEnabled) { this.searchToolEnabled = searchToolEnabled; }
}
