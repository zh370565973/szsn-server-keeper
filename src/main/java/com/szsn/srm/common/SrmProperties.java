package com.szsn.srm.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "srm")
public class SrmProperties {
    private String timezone = "Asia/Shanghai";
    private String dataDir = "./data";
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

    public Admin getAdmin() {
        return admin;
    }

    public Remote getRemote() {
        return remote;
    }

    public static class Admin {
        private String username = "admin";
        private String passwordFile = "./data/admin.password";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
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
