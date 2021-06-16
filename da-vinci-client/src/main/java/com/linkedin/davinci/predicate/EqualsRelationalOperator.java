package com.linkedin.davinci.predicate;

import com.linkedin.venice.annotation.Experimental;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import java.util.Objects;
import org.apache.avro.generic.GenericRecord;


class EqualsRelationalOperator implements Predicate{
  private final String fieldName;
  private final Object expectedValue;

  public EqualsRelationalOperator(String fieldName, Object expectedValue){
    if (null == fieldName){
      throw new VeniceClientException("fieldName cannot be null.");
    }
    this.fieldName = fieldName;
    this.expectedValue = expectedValue;
  }

  @Override
  public boolean evaluate(GenericRecord dataRecord) {
    if (null == dataRecord) {
      return false;
    } else {
      return Objects.deepEquals(dataRecord.get(fieldName), expectedValue);
    }
  }

  @Experimental
  public String getFieldName(){
    return fieldName;
  }

  @Experimental
  public Object getExpectedValue(){
    return expectedValue;
  }
}