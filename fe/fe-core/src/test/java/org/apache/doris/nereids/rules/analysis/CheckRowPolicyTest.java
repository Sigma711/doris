// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.analysis.TablePattern;
import org.apache.doris.analysis.UserDesc;
import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.AccessPrivilege;
import org.apache.doris.catalog.AccessPrivilegeWithCols;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.common.FeConstants;
import org.apache.doris.mysql.privilege.AccessControllerManager;
import org.apache.doris.mysql.privilege.DataMaskPolicy;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.analyzer.UnboundRelation;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.StatementScopeIdGenerator;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.commands.CreateUserCommand;
import org.apache.doris.nereids.trees.plans.commands.GrantTablePrivilegeCommand;
import org.apache.doris.nereids.trees.plans.commands.info.CreateUserInfo;
import org.apache.doris.nereids.trees.plans.logical.LogicalCheckPolicy;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalRelation;
import org.apache.doris.nereids.util.PlanRewriter;
import org.apache.doris.utframe.TestWithFeService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class CheckRowPolicyTest extends TestWithFeService {

    private static String dbName = "check_row_policy";
    private static String fullDbName = "" + dbName;
    private static String tableName = "table1";

    private static String tableNameRanddomDist = "tableRandomDist";
    private static String userName = "user1";
    private static String policyName = "policy1";

    private static OlapTable olapTable;

    @Override
    protected void runBeforeAll() throws Exception {
        FeConstants.runningUnitTest = true;
        createDatabase(dbName);
        useDatabase(dbName);
        createTable("create table "
                + tableName
                + " (k1 int, k2 int) distributed by hash(k1) buckets 1"
                + " properties(\"replication_num\" = \"1\");");
        createTable("create table "
                + tableNameRanddomDist
                + " (k1 int, k2 int) AGGREGATE KEY(k1, k2) distributed by random buckets 1"
                + " properties(\"replication_num\" = \"1\");");
        Database db = Env.getCurrentInternalCatalog().getDbOrMetaException(fullDbName);
        olapTable = (OlapTable) db.getTableOrAnalysisException(tableName);

        // create user
        UserIdentity user = new UserIdentity(userName, "%");
        user.analyze();

        CreateUserCommand createUserCommand = new CreateUserCommand(new CreateUserInfo(new UserDesc(user)));
        createUserCommand.getInfo().validate();
        Env.getCurrentEnv().getAuth().createUser(createUserCommand.getInfo());

        List<AccessPrivilegeWithCols> privileges = Lists
                .newArrayList(new AccessPrivilegeWithCols(AccessPrivilege.ADMIN_PRIV));
        TablePattern tablePattern = new TablePattern("*", "*", "*");
        tablePattern.analyze();
        GrantTablePrivilegeCommand grantTablePrivilegeCommand = new GrantTablePrivilegeCommand(privileges, tablePattern, Optional.of(user), Optional.empty());
        grantTablePrivilegeCommand.validate();
        Env.getCurrentEnv().getAuth().grantTablePrivilegeCommand(grantTablePrivilegeCommand);

        new MockUp<AccessControllerManager>() {
            @Mock
            public Optional<DataMaskPolicy> evalDataMaskPolicy(UserIdentity currentUser, String ctl,
                    String db, String tbl, String col) {
                return tbl.equalsIgnoreCase(tableNameRanddomDist)
                        ? Optional.of(new DataMaskPolicy() {
                            @Override
                            public String getMaskTypeDef() {
                                return String.format("concat(%s, '_****_', %s)", col, col);
                            }

                            @Override
                            public String getPolicyIdent() {
                                return String.format("custom policy: concat(%s, '_****_', %s)", col,
                                        col);
                            }
                        })
                        : Optional.empty();
            }
        };
    }

    @Test
    public void checkUser() throws AnalysisException, org.apache.doris.common.AnalysisException {
        LogicalRelation relation = new LogicalOlapScan(StatementScopeIdGenerator.newRelationId(), olapTable,
                Arrays.asList(fullDbName));
        LogicalCheckPolicy<LogicalRelation> checkPolicy = new LogicalCheckPolicy<>(relation);

        useUser("root");
        Plan plan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy());
        Assertions.assertEquals(plan, relation);

        useUser("notFound");
        plan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy());
        Assertions.assertEquals(plan, relation);
    }

    @Test
    public void checkUserRandomDist() throws AnalysisException, org.apache.doris.common.AnalysisException {
        connectContext.getState().setIsQuery(true);
        Plan plan = PlanRewriter.bottomUpRewrite(new UnboundRelation(StatementScopeIdGenerator.newRelationId(),
                        ImmutableList.of(tableNameRanddomDist)), connectContext, new BindRelation());
        LogicalCheckPolicy checkPolicy = new LogicalCheckPolicy(plan);

        useUser("root");
        Plan rewrittenPlan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy(),
                new BindExpression());
        Assertions.assertEquals(plan, rewrittenPlan);

        useUser("notFound");
        rewrittenPlan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy(),
                new BindExpression());
        Assertions.assertEquals(plan, rewrittenPlan.child(0));
    }

    @Test
    public void checkNoPolicy() throws org.apache.doris.common.AnalysisException {
        useUser(userName);
        LogicalRelation relation = new LogicalOlapScan(StatementScopeIdGenerator.newRelationId(), olapTable,
                Arrays.asList(fullDbName));
        LogicalCheckPolicy<LogicalRelation> checkPolicy = new LogicalCheckPolicy<>(relation);
        Plan plan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy());
        Assertions.assertEquals(plan, relation);
    }

    @Test
    public void checkNoPolicyRandomDist() throws org.apache.doris.common.AnalysisException {
        useUser(userName);
        connectContext.getState().setIsQuery(true);
        Plan plan = PlanRewriter.bottomUpRewrite(new UnboundRelation(StatementScopeIdGenerator.newRelationId(),
                ImmutableList.of(tableNameRanddomDist)), connectContext, new BindRelation());
        LogicalCheckPolicy checkPolicy = new LogicalCheckPolicy(plan);
        Plan rewrittenPlan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy(),
                new BindExpression());
        Assertions.assertEquals(plan, rewrittenPlan.child(0));
    }

    @Test
    public void checkOnePolicy() throws Exception {
        useUser(userName);
        LogicalRelation relation = new LogicalOlapScan(StatementScopeIdGenerator.newRelationId(), olapTable,
                Arrays.asList(fullDbName));
        LogicalCheckPolicy<LogicalRelation> checkPolicy = new LogicalCheckPolicy<>(relation);
        createPolicy("CREATE ROW POLICY "
                + policyName
                + " ON "
                + tableName
                + " AS PERMISSIVE TO "
                + userName
                + " USING (k1 = 1)");
        Plan plan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy());

        Assertions.assertTrue(plan instanceof LogicalFilter);
        LogicalFilter filter = (LogicalFilter) plan;
        Assertions.assertEquals(filter.child(), relation);
        Assertions.assertTrue(ImmutableList.copyOf(filter.getConjuncts()).get(0) instanceof EqualTo);
        Assertions.assertTrue(filter.getConjuncts().toString().contains("'k1 = 1"));

        dropPolicy("DROP ROW POLICY "
                + policyName
                + " ON "
                + tableName);
    }

    @Test
    public void checkOnePolicyRandomDist() throws Exception {
        useUser(userName);
        connectContext.getState().setIsQuery(true);
        connectContext.setStatementContext(new StatementContext());
        Plan plan = PlanRewriter.bottomUpRewrite(new UnboundRelation(StatementScopeIdGenerator.newRelationId(),
                ImmutableList.of(tableNameRanddomDist)), connectContext, new BindRelation());

        LogicalCheckPolicy checkPolicy = new LogicalCheckPolicy(plan);
        createPolicy("CREATE ROW POLICY "
                + policyName
                + " ON "
                + tableNameRanddomDist
                + " AS PERMISSIVE TO "
                + userName
                + " USING (k1 = 1)");
        Plan rewrittenPlan = PlanRewriter.bottomUpRewrite(checkPolicy, connectContext, new CheckPolicy(),
                new BindExpression());

        Assertions.assertTrue(rewrittenPlan instanceof LogicalProject
                && rewrittenPlan.child(0) instanceof LogicalFilter);
        LogicalFilter filter = (LogicalFilter) rewrittenPlan.child(0);
        Assertions.assertEquals(filter.child(), plan);
        Assertions.assertTrue(ImmutableList.copyOf(filter.getConjuncts()).get(0) instanceof EqualTo);
        Assertions.assertTrue(filter.getConjuncts().toString().contains("k1#0 = 1"));

        dropPolicy("DROP ROW POLICY "
                + policyName
                + " ON "
                + tableNameRanddomDist);
    }
}
