/*
 * Copyright 2015 eleflow.com.br.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.param.shared

import org.apache.spark.ml.param.{Param, Params}

/**
  * Created by dirceu on 28/04/16.
  */
trait HasValidationCol extends Params {

  /**
    * Param for label column name.
    *
    * @group param
    */
  final val validationCol: Param[String] =
    new Param[String](this, "validationCol", "validation column name")

  setDefault(validationCol, "validation")

  /** @group getParam */
  final def getValidationCol: String = $(validationCol)
}
