// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.query.filter;

import com.google.common.hash.HashCode;

/**
 * A named filter referenced in queries. The filter can be a single
 * filter or a chain of multiple filters.
 * 
 * @since 3.0
 */
public interface NamedFilter {

  /** @return The non-null and non-empty filter name. */
  public String getId();
  
  /** @return The non-null query filter. */
  public QueryFilter getFilter();

  /** @return A HashCode object for deterministic, non-secure hashing */
  public HashCode buildHashCode();
  
}
