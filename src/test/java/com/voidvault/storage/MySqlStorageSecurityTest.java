package com.voidvault.storage;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlStorageSecurityTest {

    @Test
    void acceptsSafeDatabaseIdentifier() {
        assertEquals("voidvault_prod_2",
                MySqlStorage.requireSafeIdentifier("voidvault_prod_2", "database"));
    }

    @Test
    void rejectsSqlAndJdbcUrlInjectionInDatabaseIdentifier() {
        assertThrows(IllegalArgumentException.class,
                () -> MySqlStorage.requireSafeIdentifier("vault`; DROP DATABASE mysql; --", "database"));
        assertThrows(IllegalArgumentException.class,
                () -> MySqlStorage.requireSafeIdentifier("vault?useSSL=false", "database"));
    }

    @Test
    void rejectsHostAndSslModeConnectionStringInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> MySqlStorage.requireSafeHost("db.example?sslMode=DISABLED", "host"));
        assertThrows(IllegalArgumentException.class,
                () -> MySqlStorage.requireSslMode("VERIFY_IDENTITY&allowPublicKeyRetrieval=true"));
        assertEquals("VERIFY_IDENTITY", MySqlStorage.requireSslMode("verify_identity"));
    }

    @Test
    void temporaryDatabaseCreationPoolUsesConfiguredTimeout() {
        HikariConfig config = MySqlStorage.buildTempConfig(
                "jdbc:mysql://db.example:3306/", "voidvault", "secret", 3_000);

        assertEquals(3_000, config.getConnectionTimeout());
        assertEquals(2_000, config.getValidationTimeout());
        assertEquals(1, config.getMaximumPoolSize());
    }

    @Test
    void connectionFailureSummaryKeepsSqlStateAndRootCause() {
        SQLException root = new SQLException("Connection refused", "08S01", 0);
        SQLException wrapper = new SQLException("Retry exhausted", "08001", 17);
        wrapper.setNextException(root);

        String summary = MySqlStorage.describeConnectionFailure(wrapper);

        org.junit.jupiter.api.Assertions.assertTrue(summary.contains("SQLState=08S01"));
        org.junit.jupiter.api.Assertions.assertTrue(summary.contains("Connection refused"));
    }
}
