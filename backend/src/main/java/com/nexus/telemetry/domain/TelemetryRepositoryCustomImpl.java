package com.nexus.telemetry.domain;

import com.nexus.telemetry.domain.TelemetryQuery;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepositoryCustom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TelemetryRepositoryCustomImpl implements TelemetryRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<TelemetryRecord> findLatest(int limit) {
        String jpql = "SELECT t FROM TelemetryRecord t ORDER BY t.timestamp DESC";
        TypedQuery<TelemetryRecord> query = entityManager.createQuery(jpql, TelemetryRecord.class);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    @Override
    public List<TelemetryRecord> query(TelemetryQuery query) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM TelemetryRecord t WHERE t.deviceId = :deviceId");

        if (query.after() != null) {
            jpql.append(" AND t.timestamp > :after");
        }
        if (query.before() != null) {
            jpql.append(" AND t.timestamp < :before");
        }

        if ("desc".equalsIgnoreCase(query.sortDirection())) {
            jpql.append(" ORDER BY t.timestamp DESC");
        } else {
            jpql.append(" ORDER BY t.timestamp ASC");
        }

        TypedQuery<TelemetryRecord> typedQuery = entityManager.createQuery(jpql.toString(), TelemetryRecord.class);
        typedQuery.setParameter("deviceId", query.deviceId());

        if (query.after() != null) {
            typedQuery.setParameter("after", query.after());
        }
        if (query.before() != null) {
            typedQuery.setParameter("before", query.before());
        }

        typedQuery.setMaxResults(query.limit());

        return typedQuery.getResultList();
    }
}
