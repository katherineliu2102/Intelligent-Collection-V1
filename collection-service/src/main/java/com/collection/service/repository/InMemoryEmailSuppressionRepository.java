package com.collection.service.repository;

import com.collection.common.model.EmailSuppression;
import com.collection.common.repository.EmailSuppressionRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 本地 / CI 默认抑制名单（与 {@code MockCaseService} 同层）。进程内存，跨重启丢失，不可用于 Pilot / 生产； {@code
 * collection.case-service=ai} 时由 {@link MysqlEmailSuppressionRepository} 顶掉。
 */
@Repository
public class InMemoryEmailSuppressionRepository implements EmailSuppressionRepository {

    private final Set<String> suppressed = ConcurrentHashMap.newKeySet();

    @Override
    public void suppress(EmailSuppression suppression) {
        String normalized = EmailSuppressionSupport.normalize(suppression.getEmail());
        if (normalized != null) {
            suppressed.add(normalized);
        }
    }

    @Override
    public boolean isSuppressed(String email) {
        String normalized = EmailSuppressionSupport.normalize(email);
        return normalized != null && suppressed.contains(normalized);
    }
}
