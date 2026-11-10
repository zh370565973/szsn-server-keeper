package com.szsn.srm.system;

import com.szsn.srm.common.SrmProperties;
import java.net.InetAddress;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(name = "srm.mode", havingValue = "server", matchIfMissing = true)
public class RemoteBindingGuard implements ApplicationRunner {
    private final ServerProperties serverProperties;
    private final SrmProperties properties;

    public RemoteBindingGuard(ServerProperties serverProperties, SrmProperties properties) {
        this.serverProperties = serverProperties;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        InetAddress address = serverProperties.getAddress();
        if (address == null || address.isLoopbackAddress()) {
            return;
        }
        boolean https = serverProperties.getSsl() != null
                && serverProperties.getSsl().isEnabled()
                && serverProperties.getSsl().getKeyStore() != null
                && !serverProperties.getSsl().getKeyStore().isBlank();
        boolean cidr = properties.getRemote().getCidr() != null && !properties.getRemote().getCidr().isBlank();
        if (!https || !cidr) {
            throw new IllegalStateException("非回环监听必须同时配置内置 HTTPS 证书和 srm.remote.cidr");
        }
    }
}
