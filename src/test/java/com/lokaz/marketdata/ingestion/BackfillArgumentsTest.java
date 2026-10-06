package com.lokaz.marketdata.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class BackfillArgumentsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private static BackfillArguments parse(String... args) {
        return BackfillArguments.parse(new DefaultApplicationArguments(args), TODAY, List.of("SPY", "QQQ"));
    }

    @Test
    void parsesAllOptions() {
        var parsed = parse("--from=2016-01-01", "--to=2020-12-31", "--symbols=aapl,MSFT");
        assertThat(parsed.from()).isEqualTo(LocalDate.of(2016, 1, 1));
        assertThat(parsed.to()).isEqualTo(LocalDate.of(2020, 12, 31));
        assertThat(parsed.symbols()).containsExactly("AAPL", "MSFT");
        assertThat(parsed.kind()).isEqualTo(IngestionKind.BACKFILL);
    }

    @Test
    void scheduledRunsCanLabelThemselvesDaily() {
        assertThat(parse("--from=2026-09-28", "--kind=daily").kind()).isEqualTo(IngestionKind.DAILY);
        assertThatThrownBy(() -> parse("--from=2026-09-28", "--kind=weekly")).hasMessageContaining("--kind");
    }

    @Test
    void defaultsToTodayAndTheConfiguredSymbols() {
        var parsed = parse("--from=2024-01-01");
        assertThat(parsed.to()).isEqualTo(TODAY);
        assertThat(parsed.symbols()).containsExactly("SPY", "QQQ");
    }

    @Test
    void requiresFrom() {
        assertThatThrownBy(() -> parse("--to=2024-01-01")).hasMessageContaining("--from");
    }

    @Test
    void rejectsBadDates() {
        assertThatThrownBy(() -> parse("--from=01/02/2024")).hasMessageContaining("YYYY-MM-DD");
    }
}
