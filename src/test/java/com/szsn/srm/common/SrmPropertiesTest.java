package com.szsn.srm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SrmPropertiesTest {
    @Test
    void locationsFollowTheConfigInsteadOfFixedInstallPaths() {
        SrmProperties properties = new SrmProperties();
        Map<String, String> defaults = properties.locations();
        assertEquals("./data/server-resource-manager", defaults.get("jar"));
        assertEquals("./data/server-resource-manager/application.yml", defaults.get("config"));
        assertEquals("./data/server-resource-manager", defaults.get("data"));
        assertEquals("./data/server-resource-manager", defaults.get("log"));

        properties.getDeploy().setJar("D:/portable/app");
        properties.getDeploy().setConfig("D:/portable/config");
        properties.getDeploy().setLog("D:/portable/logs");
        properties.setDataDir("D:/portable/data");

        Map<String, String> moved = properties.locations();
        assertEquals("D:/portable/app", moved.get("jar"));
        assertEquals("D:/portable/config", moved.get("config"));
        assertEquals("D:/portable/data", moved.get("data"));
        assertEquals("D:/portable/logs", moved.get("log"));
    }
}
