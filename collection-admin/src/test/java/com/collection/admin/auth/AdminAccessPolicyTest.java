package com.collection.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AdminAccessPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "VIEWER,GET,/dashboard/today,true",
        "VIEWER,GET,/ops/dlq/redrive,true",
        "VIEWER,POST,/ops/exceptions/1/ack,false",
        "VIEWER,PUT,/config/script-templates,false",
        "VIEWER,POST,/compliance/freeze,false",
        "OPERATOR,GET,/cases/search,true",
        "OPERATOR,POST,/ops/exceptions/1/ack,true",
        "OPERATOR,POST,/ops/exceptions/1/resolve,true",
        "OPERATOR,PUT,/config/script-templates,true",
        "OPERATOR,PUT,/config/plan-templates,true",
        "OPERATOR,PUT,/config/evaluation-settings,true",
        "OPERATOR,POST,/compliance/freeze,true",
        "OPERATOR,POST,/config/rollback,false",
        "OPERATOR,POST,/ops/dlq/redrive,false",
        "OPERATOR,POST,/ops/plans/rebuild-strategy,false",
        "OPERATOR,POST,/ops/fault-injection/arm,false",
        "OPERATOR,DELETE,/ops/fault-injection,false",
        "OPERATOR,POST,/plans/1/cancel,false",
        "OPERATOR,PUT,/catalog/template/x,false",
        "OPERATOR,POST,/admin/users,false",
        "VIEWER,GET,/admin/accounts,false",
        "OPERATOR,GET,/admin/accounts,false",
        "SYSTEM_ADMIN,GET,/admin/accounts,true",
        "SYSTEM_ADMIN,POST,/ops/dlq/redrive,true",
        "SYSTEM_ADMIN,POST,/ops/plans/rebuild-strategy,true",
        "SYSTEM_ADMIN,POST,/config/rollback,true",
        "SYSTEM_ADMIN,DELETE,/ops/fault-injection,true",
        "SYSTEM_ADMIN,POST,/catalog/x,true"
    })
    void matrix(AdminRole role, String method, String path, boolean allowed) {
        assertThat(AdminAccessPolicy.allows(role, method, path)).isEqualTo(allowed);
    }

    @Test
    void blankMethodIsDenied() {
        assertThat(AdminAccessPolicy.allows(AdminRole.SYSTEM_ADMIN, " ", "/dashboard")).isFalse();
    }
}
