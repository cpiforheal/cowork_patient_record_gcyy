package com.example.coshare_patientrecord_sys;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class DatabaseHealthService {

    private final String url;
    private final String username;
    private final String password;

    public DatabaseHealthService(
        @Value("${spring.datasource.url}") String url,
        @Value("${spring.datasource.username}") String username,
        @Value("${spring.datasource.password:}") String password
    ) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public Map<String, Object> check() {
        long startedAt = System.currentTimeMillis();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password == null ? "" : password);

            try (
                Connection connection = DriverManager.getConnection(url, properties);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")
            ) {
                int result = resultSet.next() ? resultSet.getInt(1) : 0;
                DatabaseMetaData metadata = connection.getMetaData();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("enabled", true);
                response.put("status", "ok");
                response.put("result", result);
                response.put("database", metadata.getDatabaseProductName());
                response.put("version", metadata.getDatabaseProductVersion());
                response.put("durationMs", System.currentTimeMillis() - startedAt);
                return response;
            }
        } catch (ClassNotFoundException | SQLException error) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "数据库连接检查失败，请核对 MySQL 服务、账号权限和网络连通性",
                error
            );
        }
    }
}
