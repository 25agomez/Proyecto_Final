package cr.logisticaportuaria.optimizer.congestion.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small gradient boosting regressor with squared loss and shallow regression trees.
 *
 * <p>It is deliberately self-contained (no machine learning dependency) and
 * <strong>deterministic</strong>: there is no column or row subsampling and the candidate
 * thresholds of each feature are fixed quantiles computed once at fit time, so the same
 * history always produces the same model. The model is used to correct the residual of the
 * queueing baseline (see {@link CongestionForecaster}).
 */
public final class GradientBoostingRegressor {

    private final int trees;
    private final int maxDepth;
    private final int maxBins;
    private final int minLeaf;
    private final double learningRate;

    private double initial;
    private double[][] thresholds = new double[0][];
    private List<Node> forest = List.of();

    /** Creates the model with the default hyper-parameters. */
    public GradientBoostingRegressor() {
        this(60, 3, 8, 20, 0.1);
    }

    /**
     * Creates the model.
     *
     * @param trees        number of boosting rounds
     * @param maxDepth     maximum depth of each regression tree
     * @param maxBins      candidate thresholds per feature
     * @param minLeaf      minimum number of rows of a leaf
     * @param learningRate shrinkage applied to every tree
     */
    public GradientBoostingRegressor(int trees, int maxDepth, int maxBins, int minLeaf, double learningRate) {
        if (trees <= 0 || maxDepth <= 0 || maxBins <= 1 || minLeaf <= 0) {
            throw new IllegalArgumentException("invalid hyper-parameters");
        }
        if (learningRate <= 0.0 || learningRate > 1.0) {
            throw new IllegalArgumentException("learningRate must be in (0, 1]");
        }
        this.trees = trees;
        this.maxDepth = maxDepth;
        this.maxBins = maxBins;
        this.minLeaf = minLeaf;
        this.learningRate = learningRate;
    }

    /**
     * Fits the model.
     *
     * @param features one row per sample
     * @param target   one value per sample
     */
    public void fit(double[][] features, double[] target) {
        if (features.length != target.length) {
            throw new IllegalArgumentException("features and target must have the same length");
        }
        if (features.length == 0 || features[0].length == 0) {
            throw new IllegalArgumentException("the training set is empty");
        }
        int columns = features[0].length;
        for (double[] row : features) {
            if (row.length != columns) {
                throw new IllegalArgumentException("ragged feature matrix");
            }
        }
        initial = Arrays.stream(target).average().orElse(0.0);
        thresholds = computeThresholds(features, columns);
        double[] prediction = new double[target.length];
        Arrays.fill(prediction, initial);
        List<Node> nodes = new ArrayList<>(trees);
        int[] all = new int[target.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        for (int round = 0; round < trees; round++) {
            double[] residual = new double[target.length];
            for (int i = 0; i < target.length; i++) {
                residual[i] = target[i] - prediction[i];
            }
            Node root = build(all, features, residual, 0);
            nodes.add(root);
            for (int i = 0; i < target.length; i++) {
                prediction[i] += learningRate * root.predict(features[i]);
            }
        }
        forest = List.copyOf(nodes);
    }

    /**
     * Predicts one sample.
     *
     * @param features the features of the sample
     * @return the prediction
     */
    public double predict(double[] features) {
        if (!isFitted()) {
            throw new IllegalStateException("the model has not been fitted");
        }
        double value = initial;
        for (Node tree : forest) {
            value += learningRate * tree.predict(features);
        }
        return value;
    }

    /** @return whether the model was fitted */
    public boolean isFitted() {
        return !forest.isEmpty();
    }

    /** @return number of boosting rounds of the fitted model */
    public int size() {
        return forest.size();
    }

    private double[][] computeThresholds(double[][] features, int columns) {
        double[][] result = new double[columns][];
        for (int column = 0; column < columns; column++) {
            double[] values = new double[features.length];
            for (int i = 0; i < features.length; i++) {
                values[i] = features[i][column];
            }
            Arrays.sort(values);
            int candidates = Math.min(maxBins, values.length);
            List<Double> unique = new ArrayList<>(candidates);
            for (int b = 1; b < candidates; b++) {
                int position = (int) Math.ceil((double) b * values.length / candidates) - 1;
                position = Math.max(0, Math.min(values.length - 2, position));
                double threshold = (values[position] + values[position + 1]) / 2.0;
                if (unique.isEmpty() || unique.get(unique.size() - 1) < threshold) {
                    unique.add(threshold);
                }
            }
            double[] columnThresholds = new double[unique.size()];
            for (int i = 0; i < unique.size(); i++) {
                columnThresholds[i] = unique.get(i);
            }
            result[column] = columnThresholds;
        }
        return result;
    }

    private Node build(int[] indexes, double[][] features, double[] residual, int depth) {
        double mean = mean(residual, indexes);
        if (depth >= maxDepth || indexes.length < 2 * minLeaf) {
            return Node.leaf(mean);
        }
        Split best = null;
        for (int column = 0; column < thresholds.length; column++) {
            for (double threshold : thresholds[column]) {
                double sumLeft = 0.0;
                double sumRight = 0.0;
                double squareLeft = 0.0;
                double squareRight = 0.0;
                int countLeft = 0;
                int countRight = 0;
                for (int index : indexes) {
                    double value = residual[index];
                    if (features[index][column] <= threshold) {
                        sumLeft += value;
                        squareLeft += value * value;
                        countLeft++;
                    } else {
                        sumRight += value;
                        squareRight += value * value;
                        countRight++;
                    }
                }
                if (countLeft < minLeaf || countRight < minLeaf) {
                    continue;
                }
                double within = squaredError(squareLeft, sumLeft, countLeft)
                        + squaredError(squareRight, sumRight, countRight);
                if (best == null || within < best.within) {
                    best = new Split(column, threshold, within);
                }
            }
        }
        if (best == null) {
            return Node.leaf(mean);
        }
        int[] left = new int[indexes.length];
        int[] right = new int[indexes.length];
        int countLeft = 0;
        int countRight = 0;
        for (int index : indexes) {
            if (features[index][best.column] <= best.threshold) {
                left[countLeft++] = index;
            } else {
                right[countRight++] = index;
            }
        }
        return Node.split(best.column, best.threshold,
                build(Arrays.copyOf(left, countLeft), features, residual, depth + 1),
                build(Arrays.copyOf(right, countRight), features, residual, depth + 1));
    }

    private static double squaredError(double square, double sum, int count) {
        return count == 0 ? 0.0 : square - sum * sum / count;
    }

    private static double mean(double[] values, int[] indexes) {
        double sum = 0.0;
        for (int index : indexes) {
            sum += values[index];
        }
        return indexes.length == 0 ? 0.0 : sum / indexes.length;
    }

    private record Split(int column, double threshold, double within) {
    }

    private static final class Node {

        private final int column;
        private final double threshold;
        private final double value;
        private final Node left;
        private final Node right;

        private Node(int column, double threshold, double value, Node left, Node right) {
            this.column = column;
            this.threshold = threshold;
            this.value = value;
            this.left = left;
            this.right = right;
        }

        static Node leaf(double value) {
            return new Node(-1, 0.0, value, null, null);
        }

        static Node split(int column, double threshold, Node left, Node right) {
            return new Node(column, threshold, 0.0, left, right);
        }

        double predict(double[] features) {
            if (column < 0) {
                return value;
            }
            return features[column] <= threshold ? left.predict(features) : right.predict(features);
        }
    }
}
