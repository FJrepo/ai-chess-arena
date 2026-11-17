package dev.aichessarena.resource;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Captures only the export's test thread, excluding background jobs and fixture writes. */
@ApplicationScoped
@PersistenceUnitExtension
public class ExportSqlCapture implements StatementInspector {
    private final ThreadLocal<List<String>> statements = new ThreadLocal<>();

    public void start() {
        statements.set(new ArrayList<>());
    }

    public List<String> finish() {
        List<String> captured = List.copyOf(statements.get());
        statements.remove();
        return captured;
    }

    @Override
    public String inspect(String sql) {
        List<String> captured = statements.get();
        if (captured != null) captured.add(sql);
        return sql;
    }
}
