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

package org.apache.doris.catalog.constraint;

import com.google.gson.annotations.SerializedName;

public abstract class Constraint {
    public enum ConstraintType {
        FOREIGN_KEY("FOREIGN KEY"),
        PRIMARY_KEY("PRIMARY KEY"),
        UNIQUE("UNIQUE");
        @SerializedName(value = "tn")
        private final String name;

        ConstraintType(String stringValue) {
            this.name = stringValue;
        }

        public String getName() {
            return name;
        }
    }

    @SerializedName(value = "n")
    private final String name;
    @SerializedName(value = "ty")
    private final ConstraintType type;


    protected Constraint(ConstraintType type, String name) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public ConstraintType getType() {
        return type;
    }
}
