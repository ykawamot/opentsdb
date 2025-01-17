// This file is part of OpenTSDB.
// Copyright (C) 2017-2018 The OpenTSDB Authors.
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
package net.opentsdb.query.serdes;

import java.util.List;

/**
 * Interface used to encapsulate Serdes specific options at query time.
 * 
 * @since 3.0
 */
public interface SerdesOptions {
  
  /** @return The ID of this serdes option. */
  public String getId();
  
  /** @return The required ID of the serializer factory to instantiate
   * a serdes instance from. */
  public String getType();
  
  /** @return A non-null filter of nodes to serialize in the sink. If the
   * list is empty, then all root sources are automatically serialized. */
  public List<String> getFilter();
  
}
