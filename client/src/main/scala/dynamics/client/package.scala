// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics

package object client {
  val DefaultDynamicsOptions = DynamicsOptions()

  /** Boolean header value to suppress duplicate detection. */
  val SuppressDuplicateDetection = "MSCRM.SuppressDuplicateDetection"

  /** Header for impersonating. Value sould be the systemuserid. */
  val Impersonate = "MSCRMCallerID"

}