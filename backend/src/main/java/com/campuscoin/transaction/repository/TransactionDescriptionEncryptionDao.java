package com.campuscoin.transaction.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TransactionDescriptionEncryptionDao {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public int reencryptImportedDescriptions(Long userId,
                                             List<Long> transactionIds,
                                             List<String> envelopes) {
        if (transactionIds.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder("""
                UPDATE transactions
                   SET description = CASE id
                """);
        for (int i = 0; i < transactionIds.size(); i++) {
            sql.append("        WHEN :id").append(i).append(" THEN :env").append(i).append('\n');
        }
        sql.append("""
                       END
                 WHERE user_id = :userId
                   AND source = 'CSV'
                   AND id IN (:ids)
                """);

        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("userId", userId)
                .setParameter("ids", transactionIds);
        for (int i = 0; i < transactionIds.size(); i++) {
            query.setParameter("id" + i, transactionIds.get(i));
            query.setParameter("env" + i, envelopes.get(i));
        }
        return query.executeUpdate();
    }
}
