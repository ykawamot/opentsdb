// This file is part of OpenTSDB.
// Copyright (C) 2018-2020  The OpenTSDB Authors.
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
package net.opentsdb.storage;

import com.google.common.base.Strings;

/**
 * The response from a write call including the state and optional
 * error message or exception.
 * 
 * @since 3.0
 */
public interface WriteStatus {

  static final String OK_STRING = "state=OK";
  static final String RETRY_STRING = "state=RETRY";
  static final String REJECTED_STRING = "state=REJECTED";

  /**
   * An enum used by callers to determine whether or not the write was
   * successful.
   */
  public static enum WriteState {
    /** The write was successful and the original message can be dropped. */
    OK,
    
    /** The write was not successful due to issues such as waiting for a
     * UID assignment, throttling or temporary unavailability. The value
     * should be retried at a later time. */
    RETRY,
    
    /** The value was rejected due to permissions, invalid tags, the type
     * of data or another issue. The value should be dropped. */
    REJECTED,
    
    /** An error happened during storage. The value can be retried. */
    ERROR
  }
  
  /** @return The non-null state of the write. */
  public WriteState state();
  
  /** @return An optional error message, should be null if 
   * {@link WriteState#OK} is returned. */
  public String message();
  
  /** @return An optional exception. Likely set when 
   * {@link WriteState#ERROR} is returned. */
  public Throwable exception();
  
  /** @return The OK status, no error message or exception. */
  public static WriteStatus ok() {
    return OK;
  }
  
  public static WriteStatus retry() {
    return RETRY;
  }
  
  /**
   * Returns a retry status with the given message.
   * @param message An optional error message.
   * @return The retry write status.
   */
  public static WriteStatus retry(final String message) {
    if (Strings.isNullOrEmpty(message)) {
      return RETRY;
    }
    
    return new WriteStatus() {

      @Override
      public WriteState state() {
        return WriteState.RETRY;
      }

      @Override
      public String message() {
        return message;
      }

      @Override
      public Throwable exception() {
        return null;
      }
      
    };
  }
  
  public static WriteStatus rejected() {
    return REJECTED;
  }
  
  /**
   * Returns a rejected status with the given message.
   * @param message An optional error message.
   * @return The rejected write status.
   */
  public static WriteStatus rejected(final String message) {
    if (Strings.isNullOrEmpty(message)) {
      return REJECTED;
    }
    
    return new WriteStatus() {

      @Override
      public WriteState state() {
        return WriteState.REJECTED;
      }

      @Override
      public String message() {
        return message;
      }

      @Override
      public Throwable exception() {
        return null;
      }
      
    };
  }
  
  /**
   * Returns an error status with the given message and optional exception.
   * @param message An optional error message.
   * @param t An optional exception.
   * @return The error write status.
   */
  public static WriteStatus error(final String message, final Throwable t) {
    return new WriteStatus() {

      @Override
      public WriteState state() {
        return WriteState.ERROR;
      }

      @Override
      public String message() {
        return message;
      }

      @Override
      public Throwable exception() {
        return t;
      }

      @Override
      public String toString() {
        return new StringBuilder()
                .append("state=")
                .append(state())
                .append(", message='")
                .append(message)
                .append("', exception=")
                .append(t)
                .toString();
      }
      
    };
  }
  
  /** The OK status, no error message or exception. */
  public static WriteStatus OK = new WriteStatus() {

    @Override
    public WriteState state() {
      return WriteState.OK;
    }

    @Override
    public String message() {
      return null;
    }

    @Override
    public Throwable exception() {
      return null;
    }

    @Override
    public String toString() {
      return OK_STRING;
    }
    
  };

  public static final WriteStatus REJECTED = new WriteStatus() {

    @Override
    public WriteState state() {
      return WriteState.REJECTED;
    }

    @Override
    public String message() {
      return "Rejected";
    }

    @Override
    public Throwable exception() {
      return null;
    }

    @Override
    public String toString() {
      return REJECTED_STRING;
    }

  };
  
  public static final WriteStatus RETRY = new WriteStatus() {

    @Override
    public WriteState state() {
      return WriteState.RETRY;
    }

    @Override
    public String message() {
      return "Retry";
    }

    @Override
    public Throwable exception() {
      return null;
    }

    @Override
    public String toString() {
      return RETRY_STRING;
    }

  };
  
}