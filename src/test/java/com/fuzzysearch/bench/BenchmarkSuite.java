package com.fuzzysearch.bench;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs every benchmark and writes the report artifacts.
 *
 * <pre>
 *   ./mvnw test-compile exec:java@benchmark
 * </pre>
 *
 * <p>Produces three things in {@code docs/}:
 * <ul>
 *   <li>{@code benchmarks.md} -- markdown tables, ready to paste into the README</li>
 *   <li>{@code benchmark-scaling.svg} -- log-log scaling chart, rendered in the README</li>
 *   <li>{@code benchmark-data.json} -- the same numbers for the frontend benchmark page</li>
 * </ul>
 *
 * <p>JMH is driven programmatically rather than through its command line so results come back as
 * {@link RunResult} objects. No output parsing, and the report can never drift from the run that
 * produced it.
 */
public final class BenchmarkSuite {

    /**
     * Fraction of the score that the 99.9% confidence half-width may reach before a measurement
     * is treated as untrustworthy.
     *
     * <p>This exists because of a real incident: a run on a contended machine reported 131,951 µs
     * ±582,064 for one configuration whose true value was around 1,100 µs. Published unchecked,
     * that single cell would have become a "50,623× speedup" claim in the README -- the kind of
     * number that discredits every other number next to it. A benchmark report should refuse to
     * publish figures it cannot stand behind.
     */
    private static final double MAX_RELATIVE_ERROR = 0.25;

    /** One measured configuration. */
    record Point(String group, int datasetSize, String implementation, int variant,
                 double microsPerOp, double errorMicros) {

        double opsPerSecond() {
            return 1_000_000.0 / microsPerOp;
        }

        /** False when run-to-run variance is too wide for the number to mean anything. */
        boolean reliable() {
            return !Double.isNaN(errorMicros) && errorMicros <= MAX_RELATIVE_ERROR * microsPerOp;
        }
    }

    private static final Path DOCS = Path.of("docs");

    public static void main(String[] args) throws Exception {
        OptionsBuilder builder = new OptionsBuilder();
        builder.include(PrefixSearchBenchmark.class.getSimpleName())
                .include(FuzzySearchBenchmark.class.getSimpleName())
                .include(CombinedSearchBenchmark.class.getSimpleName())
                .shouldFailOnError(true);

        // -Dbench.quick=true runs the full pipeline in well under a minute at the two extreme
        // corpus sizes. The numbers are not publication quality -- one short iteration each --
        // but it validates the harness and the report generation without a ten-minute wait.
        if (Boolean.getBoolean("bench.quick")) {
            builder.warmupIterations(1).warmupTime(TimeValue.milliseconds(300))
                    .measurementIterations(1).measurementTime(TimeValue.milliseconds(300))
                    .param("datasetSize", "1000", "100000");
        }

        // -Dbench.thorough=true: two forks and more measurement iterations. Two forks matters
        // more than more iterations -- it re-randomises JIT compilation decisions and heap
        // layout, which is where run-to-run variance actually comes from.
        if (Boolean.getBoolean("bench.thorough")) {
            builder.forks(2).warmupIterations(3).measurementIterations(5);
        }

        Options options = builder.build();

        Collection<RunResult> runResults = new Runner(options).run();
        List<Point> points = toPoints(runResults);

        Files.createDirectories(DOCS);
        Files.writeString(DOCS.resolve("benchmarks.md"), markdown(points));
        Files.writeString(DOCS.resolve("benchmark-scaling.svg"), svg(points));
        Files.writeString(DOCS.resolve("benchmark-data.json"), json(points));

        System.out.println();
        System.out.println("wrote docs/benchmarks.md, docs/benchmark-scaling.svg, "
                + "docs/benchmark-data.json");

        List<Point> unreliable = points.stream().filter(p -> !p.reliable()).toList();
        if (!unreliable.isEmpty()) {
            System.out.println();
            System.out.println("WARNING: " + unreliable.size() + " of " + points.size()
                    + " measurements exceeded " + (int) (MAX_RELATIVE_ERROR * 100)
                    + "% relative error and are flagged in the report:");
            for (Point point : unreliable) {
                System.out.printf(Locale.ROOT,
                        "  %s variant=%d size=%,d %s: %.1f µs ± %.1f%n",
                        point.group(), point.variant(), point.datasetSize(),
                        point.implementation(), point.microsPerOp(), point.errorMicros());
            }
            System.out.println("Re-run on an idle machine before publishing these numbers.");
        }
    }

    // -------------------------------------------------------------------------------------
    // Collecting
    // -------------------------------------------------------------------------------------

    private static List<Point> toPoints(Collection<RunResult> runResults) {
        List<Point> points = new ArrayList<>();
        for (RunResult result : runResults) {
            String benchmark = result.getParams().getBenchmark();
            String group = benchmark.contains("PrefixSearch") ? "prefix"
                    : benchmark.contains("FuzzySearch") ? "fuzzy"
                    : "combined";

            int variant = switch (group) {
                case "prefix" -> Integer.parseInt(result.getParams().getParam("queryLength"));
                case "fuzzy" -> Integer.parseInt(result.getParams().getParam("maxEditDistance"));
                // "combined" has a string parameter, so encode it as an int to keep one shape.
                default -> "prefix".equals(result.getParams().getParam("queryType")) ? 1 : 0;
            };

            points.add(new Point(
                    group,
                    Integer.parseInt(result.getParams().getParam("datasetSize")),
                    result.getParams().getParam("implementation"),
                    variant,
                    result.getPrimaryResult().getScore(),
                    result.getPrimaryResult().getScoreError()));
        }
        points.sort(Comparator.comparing(Point::group)
                .thenComparingInt(Point::variant)
                .thenComparingInt(Point::datasetSize)
                .thenComparing(Point::implementation));
        return points;
    }

    private static Optional<Point> find(List<Point> points, String group, int variant, int size,
                                        String implementation) {
        return points.stream()
                .filter(p -> p.group().equals(group) && p.variant() == variant
                        && p.datasetSize() == size
                        && p.implementation().equals(implementation))
                .findFirst();
    }

    private static List<Integer> variants(List<Point> points, String group) {
        return points.stream().filter(p -> p.group().equals(group))
                .map(Point::variant).distinct().sorted().toList();
    }

    private static List<Integer> sizes(List<Point> points, String group) {
        return points.stream().filter(p -> p.group().equals(group))
                .map(Point::datasetSize).distinct().sorted().toList();
    }

    // -------------------------------------------------------------------------------------
    // Markdown
    // -------------------------------------------------------------------------------------

    /** Set while rendering if any row was flagged, so the header can carry the caveat. */
    private static boolean unreliableRowsPresent;

    private static String markdown(List<Point> points) {
        unreliableRowsPresent = false;
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark Results\n\n");
        md.append("Generated ").append(LocalDate.now()).append(" by `BenchmarkSuite`. ")
                .append("Measured with JMH: 1 fork, 3 warmup iterations, 5 measurement ")
                .append("iterations, average time per query, ")
                .append(BenchmarkCorpus.LIMIT).append(" results requested.\n\n");
        md.append("`±` is JMH's 99.9% confidence half-width. Each benchmark rotates through 16 ")
                .append("queries so no single lucky prefix dominates the average.\n\n");
        md.append("![scaling](benchmark-scaling.svg)\n\n");

        StringBuilder body = new StringBuilder();
        appendGroup(body, points, "prefix", "Prefix search — linear scan vs. trie");
        appendGroup(body, points, "fuzzy", "Fuzzy search — linear Levenshtein scan vs. BK-tree");
        appendGroup(body, points, "combined",
                "End-to-end `search()` — prefix + fuzzy, with progressive relaxation");

        if (unreliableRowsPresent) {
            md.append("> ⚠️ Rows marked ⚠️ had a confidence interval wider than ")
                    .append((int) (MAX_RELATIVE_ERROR * 100))
                    .append("% of the measured value, usually because the machine was busy. ")
                    .append("Treat them as indicative only and re-run on an idle machine.\n\n");
        }
        md.append(body);
        return md.toString();
    }

    /** Renders the parameter that distinguishes the tables inside one benchmark group. */
    private static String variantLabel(String group, int variant) {
        return switch (group) {
            case "prefix" -> "Query length " + variant;
            case "fuzzy" -> "Max edit distance " + variant;
            default -> variant == 1
                    ? "Partial word, 4 characters — a typical keystroke, prefix short-circuit fires"
                    : "Complete misspelled word — worst case, few prefix matches to short-circuit on";
        };
    }

    private static void appendGroup(StringBuilder md, List<Point> points, String group,
                                    String heading) {
        md.append("## ").append(heading).append("\n\n");
        for (int variant : variants(points, group)) {
            md.append("### ").append(variantLabel(group, variant)).append("\n\n");
            md.append("| dataset | naive | naive qps | optimized | optimized qps | speedup |\n");
            md.append("|---:|---:|---:|---:|---:|---:|\n");
            for (int size : sizes(points, group)) {
                Optional<Point> naive = find(points, group, variant, size, "naive");
                Optional<Point> optimized = find(points, group, variant, size, "optimized");
                if (naive.isEmpty() || optimized.isEmpty()) {
                    continue;
                }
                double speedup = naive.get().microsPerOp() / optimized.get().microsPerOp();
                boolean trustworthy = naive.get().reliable() && optimized.get().reliable();
                if (!trustworthy) {
                    unreliableRowsPresent = true;
                }
                md.append(String.format(Locale.ROOT,
                        "| %,d | %s | %,.0f | %s | %,.0f | %s |%n",
                        size,
                        formatMicros(naive.get()), naive.get().opsPerSecond(),
                        formatMicros(optimized.get()), optimized.get().opsPerSecond(),
                        trustworthy ? "**" + formatSpeedup(speedup) + "**"
                                : formatSpeedup(speedup) + " ⚠️"));
            }
            md.append('\n');
        }
    }

    private static String formatMicros(Point point) {
        String value = point.microsPerOp() >= 100
                ? String.format(Locale.ROOT, "%,.0f µs", point.microsPerOp())
                : String.format(Locale.ROOT, "%.2f µs", point.microsPerOp());
        if (Double.isNaN(point.errorMicros())) {
            return value;
        }
        return value + (point.errorMicros() >= 100
                ? String.format(Locale.ROOT, " ±%,.0f", point.errorMicros())
                : String.format(Locale.ROOT, " ±%.2f", point.errorMicros()));
    }

    private static String formatSpeedup(double speedup) {
        if (speedup >= 10) {
            return String.format(Locale.ROOT, "%,.0f×", speedup);
        }
        return String.format(Locale.ROOT, "%.2f×", speedup);
    }

    // -------------------------------------------------------------------------------------
    // SVG scaling chart
    // -------------------------------------------------------------------------------------

    private static final String NAIVE_COLOR = "#d1495b";
    private static final String OPTIMIZED_COLOR = "#2a9d8f";
    /** Mid-grey, legible against both a light and a dark README background. */
    private static final String AXIS_COLOR = "#8a8f98";

    private record Panel(String title, String group, int variant) {
    }

    private static String svg(List<Point> points) {
        List<Panel> panels = List.of(
                new Panel("Prefix search (3-char query)", "prefix", 3),
                new Panel("Fuzzy search (distance 1)", "fuzzy", 1),
                new Panel("Fuzzy search (distance 2)", "fuzzy", 2));

        final int panelWidth = 320;
        final int panelHeight = 250;
        final int width = panels.size() * panelWidth + 40;
        final int height = panelHeight + 110;

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" "
                        + "height=\"%d\" font-family=\"system-ui,-apple-system,Segoe UI,sans-serif\">%n",
                width, height, width, height));
        svg.append(String.format(Locale.ROOT,
                "<text x=\"%d\" y=\"26\" text-anchor=\"middle\" font-size=\"15\" "
                        + "font-weight=\"600\" fill=\"%s\">Naive vs. optimized: cost per query as "
                        + "the corpus grows (log-log)</text>%n",
                width / 2, AXIS_COLOR));

        for (int i = 0; i < panels.size(); i++) {
            svg.append(panel(points, panels.get(i), 20 + i * panelWidth, 50,
                    panelWidth - 30, panelHeight));
        }

        int legendY = height - 22;
        svg.append(legendLine(width / 2 - 150, legendY, NAIVE_COLOR, "naive (linear scan)"));
        svg.append(legendLine(width / 2 + 20, legendY, OPTIMIZED_COLOR, "optimized (trie / BK-tree)"));
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String legendLine(int x, int y, String color, String label) {
        return String.format(Locale.ROOT,
                "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2.5\"/>"
                        + "<circle cx=\"%d\" cy=\"%d\" r=\"3\" fill=\"%s\"/>"
                        + "<text x=\"%d\" y=\"%d\" font-size=\"12\" fill=\"%s\">%s</text>%n",
                x, y, x + 22, y, color, x + 11, y, color, x + 30, y + 4, AXIS_COLOR, label);
    }

    private static String panel(List<Point> points, Panel panel, int originX, int originY,
                                int plotWidth, int plotHeight) {
        List<Integer> sizes = sizes(points, panel.group());
        List<Point> series = points.stream()
                .filter(p -> p.group().equals(panel.group()) && p.variant() == panel.variant())
                .toList();
        if (series.isEmpty()) {
            return "";
        }

        double minMicros = series.stream().mapToDouble(Point::microsPerOp).min().orElse(1);
        double maxMicros = series.stream().mapToDouble(Point::microsPerOp).max().orElse(10);
        double logMin = Math.floor(Math.log10(minMicros));
        double logMax = Math.ceil(Math.log10(maxMicros));
        if (logMax - logMin < 1) {
            logMax = logMin + 1;
        }

        double logSizeMin = Math.log10(sizes.get(0));
        double logSizeMax = Math.log10(sizes.get(sizes.size() - 1));

        StringBuilder out = new StringBuilder();
        int plotTop = originY + 22;
        int plotBottom = originY + plotHeight;
        int plotLeft = originX + 44;
        int plotRight = originX + plotWidth;

        out.append(String.format(Locale.ROOT,
                "<text x=\"%d\" y=\"%d\" font-size=\"12\" font-weight=\"600\" fill=\"%s\">%s</text>%n",
                plotLeft - 38, originY + 12, AXIS_COLOR, panel.title()));

        // Horizontal gridlines, one per decade of microseconds.
        for (int decade = (int) logMin; decade <= (int) logMax; decade++) {
            double y = plotBottom - (decade - logMin) / (logMax - logMin) * (plotBottom - plotTop);
            out.append(String.format(Locale.ROOT,
                    "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"%s\" "
                            + "stroke-width=\"0.5\" opacity=\"0.3\"/>%n",
                    plotLeft, y, plotRight, y, AXIS_COLOR));
            out.append(String.format(Locale.ROOT,
                    "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\" font-size=\"10\" "
                            + "fill=\"%s\">%s</text>%n",
                    plotLeft - 6, y + 3, AXIS_COLOR, microsLabel(decade)));
        }

        // X axis ticks, one per dataset size.
        for (int size : sizes) {
            double x = plotLeft + (Math.log10(size) - logSizeMin) / (logSizeMax - logSizeMin)
                    * (plotRight - plotLeft);
            out.append(String.format(Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" "
                            + "fill=\"%s\">%s</text>%n",
                    x, plotBottom + 16, AXIS_COLOR, sizeLabel(size)));
        }
        out.append(String.format(Locale.ROOT,
                "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" font-size=\"10\" fill=\"%s\">"
                        + "corpus size</text>%n",
                (plotLeft + plotRight) / 2.0, plotBottom + 32, AXIS_COLOR));

        for (String implementation : List.of("naive", "optimized")) {
            String color = implementation.equals("naive") ? NAIVE_COLOR : OPTIMIZED_COLOR;
            StringBuilder path = new StringBuilder();
            StringBuilder markers = new StringBuilder();

            for (int size : sizes) {
                Optional<Point> point = find(points, panel.group(), panel.variant(), size,
                        implementation);
                if (point.isEmpty()) {
                    continue;
                }
                double x = plotLeft + (Math.log10(size) - logSizeMin) / (logSizeMax - logSizeMin)
                        * (plotRight - plotLeft);
                double y = plotBottom
                        - (Math.log10(point.get().microsPerOp()) - logMin) / (logMax - logMin)
                        * (plotBottom - plotTop);
                path.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
                markers.append(String.format(Locale.ROOT,
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" fill=\"%s\"/>%n", x, y, color));
            }
            out.append(String.format(Locale.ROOT,
                    "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"2.5\"/>%n",
                    path.toString().trim(), color));
            out.append(markers);
        }
        return out.toString();
    }

    private static String microsLabel(int decade) {
        return switch (decade) {
            case 0 -> "1µs";
            case 1 -> "10µs";
            case 2 -> "100µs";
            case 3 -> "1ms";
            case 4 -> "10ms";
            case 5 -> "100ms";
            default -> Math.pow(10, decade) + "µs";
        };
    }

    private static String sizeLabel(int size) {
        return size >= 1000 ? (size / 1000) + "k" : String.valueOf(size);
    }

    // -------------------------------------------------------------------------------------
    // JSON for the frontend benchmark page
    // -------------------------------------------------------------------------------------

    private static String json(List<Point> points) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"generated\": \"").append(LocalDate.now()).append("\",\n");
        out.append("  \"limit\": ").append(BenchmarkCorpus.LIMIT).append(",\n");
        out.append("  \"harness\": \"JMH 1.37, 1 fork, 3 warmup + 5 measurement iterations, "
                + "average time per query\",\n");
        out.append("  \"series\": [\n");

        LinkedHashSet<String> rows = new LinkedHashSet<>();
        for (String group : List.of("prefix", "fuzzy", "combined")) {
            for (int variant : variants(points, group)) {
                for (int size : sizes(points, group)) {
                    Optional<Point> naive = find(points, group, variant, size, "naive");
                    Optional<Point> optimized = find(points, group, variant, size, "optimized");
                    if (naive.isEmpty() || optimized.isEmpty()) {
                        continue;
                    }
                    // "reliable" must travel with the numbers. The markdown flags shaky rows
                    // with a marker, but the frontend reads this file -- and without the flag it
                    // would plot a contention artifact as fact. A single absurd point on a chart
                    // discredits every honest point beside it, so the guard has to cover both
                    // output channels, not just the one a human reads.
                    rows.add(String.format(Locale.ROOT,
                            "    {\"group\": \"%s\", \"variant\": %d, \"datasetSize\": %d, "
                                    + "\"naiveMicros\": %.3f, \"optimizedMicros\": %.3f, "
                                    + "\"speedup\": %.3f, \"reliable\": %b}",
                            group, variant, size, naive.get().microsPerOp(),
                            optimized.get().microsPerOp(),
                            naive.get().microsPerOp() / optimized.get().microsPerOp(),
                            naive.get().reliable() && optimized.get().reliable()));
                }
            }
        }
        out.append(String.join(",\n", rows));
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private BenchmarkSuite() {
    }
}
