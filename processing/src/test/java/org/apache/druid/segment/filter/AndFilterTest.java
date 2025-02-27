/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.filter;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.InputRowParser;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.impl.TimeAndDimsParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.query.filter.AndDimFilter;
import org.apache.druid.query.filter.NotDimFilter;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.query.filter.TrueDimFilter;
import org.apache.druid.segment.CursorFactory;
import org.apache.druid.segment.IndexBuilder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.Closeable;
import java.util.List;
import java.util.Map;

@RunWith(Parameterized.class)
public class AndFilterTest extends BaseFilterTest
{
  private static final String TIMESTAMP_COLUMN = "timestamp";

  private static final InputRowParser<Map<String, Object>> PARSER = new MapInputRowParser(
      new TimeAndDimsParseSpec(
          new TimestampSpec(TIMESTAMP_COLUMN, "iso", DateTimes.of("2000")),
          DimensionsSpec.EMPTY
      )
  );

  private static final RowSignature ROW_SIGNATURE = RowSignature.builder()
                                                                .add("dim0", ColumnType.STRING)
                                                                .add("dim1", ColumnType.STRING)
                                                                .add("dim2", ColumnType.STRING)
                                                                .build();

  private static final List<InputRow> ROWS = ImmutableList.of(
      makeSchemaRow(PARSER, ROW_SIGNATURE, "0", "0", "a"),
      makeSchemaRow(PARSER, ROW_SIGNATURE, "1", "0", null),
      makeSchemaRow(PARSER, ROW_SIGNATURE, "2", "0", "b"),
      makeSchemaRow(PARSER, ROW_SIGNATURE, "3", "0", null),
      makeSchemaRow(PARSER, ROW_SIGNATURE, "4", "0", "c"),
      makeSchemaRow(PARSER, ROW_SIGNATURE, "5", "0", null)
  );

  public AndFilterTest(
      String testName,
      IndexBuilder indexBuilder,
      Function<IndexBuilder, Pair<CursorFactory, Closeable>> finisher,
      boolean cnf,
      boolean optimize
  )
  {
    super(testName, ROWS, indexBuilder, finisher, cnf, optimize);
  }

  @AfterClass
  public static void tearDown() throws Exception
  {
    BaseFilterTest.tearDown(AndFilterTest.class.getName());
  }

  @Test
  public void testAnd()
  {
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "0", null),
            new SelectorDimFilter("dim1", "0", null)
        )),
        ImmutableList.of("0")
    );
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "0", null),
            new SelectorDimFilter("dim1", "1", null)
        )),
        ImmutableList.of()
    );
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "1", null),
            new SelectorDimFilter("dim1", "0", null)
        )),
        ImmutableList.of("1")
    );
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "1", null),
            new SelectorDimFilter("dim1", "1", null)
        )),
        ImmutableList.of()
    );
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new NotDimFilter(new SelectorDimFilter("dim0", "1", null)),
            new NotDimFilter(new SelectorDimFilter("dim1", "1", null))
        )),
        ImmutableList.of("0", "2", "3", "4", "5")
    );
    assertFilterMatches(
        new AndDimFilter(ImmutableList.of(
            new NotDimFilter(new SelectorDimFilter("dim0", "0", null)),
            new NotDimFilter(new SelectorDimFilter("dim1", "0", null))
        )),
        ImmutableList.of()
    );
  }

  @Test
  public void testNotAnd()
  {
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "0", null),
            new SelectorDimFilter("dim1", "0", null)
        ))),
        ImmutableList.of("1", "2", "3", "4", "5")
    );
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "0", null),
            new SelectorDimFilter("dim1", "1", null)
        ))),
        ImmutableList.of("0", "1", "2", "3", "4", "5")
    );
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "1", null),
            new SelectorDimFilter("dim1", "0", null)
        ))),
        ImmutableList.of("0", "2", "3", "4", "5")
    );
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new SelectorDimFilter("dim0", "1", null),
            new SelectorDimFilter("dim1", "1", null)
        ))),
        ImmutableList.of("0", "1", "2", "3", "4", "5")
    );
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new NotDimFilter(new SelectorDimFilter("dim0", "1", null)),
            new NotDimFilter(new SelectorDimFilter("dim1", "1", null))
        ))),
        ImmutableList.of("1")
    );
    assertFilterMatches(
        new NotDimFilter(new AndDimFilter(ImmutableList.of(
            new NotDimFilter(new SelectorDimFilter("dim0", "0", null)),
            new NotDimFilter(new SelectorDimFilter("dim1", "0", null))
        ))),
        ImmutableList.of("0", "1", "2", "3", "4", "5")
    );
  }

  @Test
  public void testNotAndWithNulls()
  {
    assertFilterMatches(
        new AndDimFilter(
            ImmutableList.of(
                TrueDimFilter.instance(),
                new SelectorDimFilter("dim2", "c", null)
            )
        ),
        ImmutableList.of("4")
    );
    assertFilterMatches(
        new NotDimFilter(
            new AndDimFilter(ImmutableList.of(
                TrueDimFilter.instance(),
                new SelectorDimFilter("dim2", "c", null)
            )
            )
        ),
        ImmutableList.of("0", "2")
    );
  }

  @Test
  public void test_equals()
  {
    EqualsVerifier.forClass(AndFilter.class).usingGetClass().withNonnullFields("filters").verify();
  }
}
