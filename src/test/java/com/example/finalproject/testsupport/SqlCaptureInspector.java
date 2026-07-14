package com.example.finalproject.testsupport;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public class SqlCaptureInspector implements StatementInspector {
    private static final ThreadLocal<List<String>> CAPTURED_SQL = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        CAPTURED_SQL.get().add(sql);
        return sql;
    }

    public static void clear() {
        CAPTURED_SQL.get().clear();
    }

    public static List<String> capturedSql() {
        return List.copyOf(CAPTURED_SQL.get());
    }
}
