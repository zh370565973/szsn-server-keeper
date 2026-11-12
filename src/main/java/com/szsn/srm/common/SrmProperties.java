package com.szsn.srm.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "srm")
public class SrmProperties {
    private String timezone = "Asia/Shanghai";
    private String dataDir = "./data/server-resource-manager";
    private final Deploy deploy = new Deploy();
    private final Admin admin = new Admin();
    private final Remote remote = new Remote();

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Deploy getDeploy() {
        return deploy;
    }

    /** 设置页「部署位置」。data 与数据库目录是同一项，避免两处各写一个路径。 */
    public Map<String, String> locations() {
        Map<String, String> locations = new LinkedHashMap<>();
        locations.put("jar", deploy.jar);
        locations.put("config", deploy.config);
        locations.put("data", dataDir);
        locations.put("log", deploy.log);
        return locations;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Remote getRemote() {
        return remote;
    }

    public static class Deploy {
        private String jar = "./data/server-resource-manager";
        private String config = "./data/server-resource-manager/application.yml";
        private String log = "./data/server-resource-manager";

        public String getJar() {
            return jar;
        }

        public void setJar(String jar) {
            this.jar = jar;
        }

        public String getConfig() {
            return config;
        }

        public void setConfig(String config) {
            this.config = config;
        }

        public String getLog() {
            return log;
        }

        public void setLog(String log) {
            this.log = log;
        }
    }

    public static class Admin {
        private String username = "admin";
        private String password = "";
        private String passwordFile = "./data/server-resource-manager/admin.password";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPasswordFile() {
            return passwordFile;
        }

        public void setPasswordFile(String passwordFile) {
            this.passwordFile = passwordFile;
        }
    }

    public static class Remote {
        private String cidr = "";

        public String getCidr() {
            return cidr;
        }

        public void setCidr(String cidr) {
            this.cidr = cidr;
        }
    }
}
