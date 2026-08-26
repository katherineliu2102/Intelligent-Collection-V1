package com.collection.service.repository;

import com.collection.common.model.EmailSuppression;
import com.collection.common.repository.EmailSuppressionRepository;
import com.collection.service.mapper.EmailSuppressionMapper;
import com.collection.service.support.ServiceClock;
import javax.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** t_email_suppression 的真实读写者；启用 collection.case-service=ai。 */
@Repository
@Primary
@ConditionalOnProperty(prefix = "collection", name = "case-service", havingValue = "ai")
public class MysqlEmailSuppressionRepository implements EmailSuppressionRepository {

    @Resource private EmailSuppressionMapper mapper;

    @Override
    public void suppress(EmailSuppression suppression) {
        String normalized = EmailSuppressionSupport.normalize(suppression.getEmail());
        if (normalized == null) {
            return;
        }
        suppression.setEmail(normalized);
        if (suppression.getCreatedAt() == null) {
            suppression.setCreatedAt(ServiceClock.now());
        }
        mapper.insertIgnore(suppression);
    }

    @Override
    public boolean isSuppressed(String email) {
        String normalized = EmailSuppressionSupport.normalize(email);
        return normalized != null && mapper.countByEmail(normalized) > 0;
    }
}
