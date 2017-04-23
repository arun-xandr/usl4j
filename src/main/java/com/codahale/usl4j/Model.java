/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codahale.usl4j;

import static java.lang.Math.abs;
import static java.lang.Math.floor;
import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

import com.google.auto.value.AutoValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import org.ejml.data.DenseMatrix64F;
import org.ejml.factory.DecompositionFactory;
import org.ejml.interfaces.decomposition.QRDecomposition;
import org.ejml.ops.CommonOps;

/**
 * A parametrized model of the Universal Scalability Law.
 */
@AutoValue
@Immutable
public abstract class Model {

  private static final int MIN_MEASUREMENTS = 6;
  private static final int DEGREE = 3;

  /**
   * A collector which will convert a stream of {@link Measurement} instances into a {@link Model}.
   *
   * @return a {@link Collector} instance
   */
  public static Collector<Measurement, ?, Model> toModel() {
    return Collector.of(ArrayList::new, List::add, (a, b) -> {
      a.addAll(b);
      return a;
    }, Model::build);
  }

  /**
   * Creates a model given the three parameters: σ, κ, and λ.
   *
   * @param sigma the coefficient of contention
   * @param kappa the coefficient of crosstalk (coherence)
   * @param lambda the throughput of the system given a single worker
   * @return a {@link Model} instance
   */
  public static Model of(double sigma, double kappa, double lambda) {
    return new AutoValue_Model(sigma, kappa, lambda);
  }

  /**
   * Given a collection of measurements, builds a {@link Model}.
   * <p>
   * Finds a set of coefficients for the equation y = λx/(1+σ(x-1)+κx(x-1)). The resulting values
   * for λ, κ, and σ are the parameters of the returned {@link Model}.
   *
   * @param measurements a collection of measurements
   * @return a {@link Model} instance
   */
  public static Model build(@Nonnull Collection<Measurement> measurements) {
    if (measurements.size() < MIN_MEASUREMENTS) {
      throw new IllegalArgumentException("Needs at least 6 measurements");
    }

    // This is not particularly pleasant code, nor is it code that I'm deeply in love with. We're
    // basically doing our own non-linear solving using a QR transform, but I would very much like
    // it if this could be closer to Stefan Moeding's R version:
    //
    //    sf.max <- max(model$y / model$x)
    //    model.fit <- nls(y ~ X1 * x/(1 + sigma * (x-1) + kappa * x * (x-1)),
    //        data = model,
    //        start = c(X1 = sf.max, sigma = 0.1, kappa = 0.01),
    //        algorithm = "port",
    //        lower = c(X1 = 0, sigma = 0, kappa = 0),
    //        upper = c(X1 = Inf, sigma = 1, kappa = 1))
    //
    // This would also allow us to determine λ via regression, as well, instead of hoping the
    // measurement with the lowest concurrency is typical of the system's unloaded behavior. I can't
    // find a Java library which does this, though, but if you're a Java numerical computing whiz
    // who's got a way of improving this that doesn't involve an old tarball of Fortran your grad
    // school advisor gave you, then I'd love to hear about improvements. I've tried a number of
    // different approaches (Least-Squares via ddogleg, etc.) and all have generated worse-fitting
    // models than this.

    final List<Measurement> points = measurements.stream()
                                                 .sorted(Comparator.comparingDouble(Measurement::n))
                                                 .collect(Collectors.toList());
    final double lambda = points.get(0).x() / points.get(0).n();

    final double[] ns = new double[points.size()];
    final double[] xs = new double[points.size()];

    for (int i = 0; i < points.size(); i++) {
      final Measurement m = points.get(i);
      ns[i] = m.n() - 1;
      xs[i] = (m.n() / (m.x() / lambda)) - 1;
    }

    final DenseMatrix64F n = new DenseMatrix64F(ns.length, DEGREE);
    for (int i = 0; i < ns.length; i++) {
      double ip = 1.0;
      for (int j = 0; j < DEGREE; j++) {
        n.set(i, j, ip);
        ip *= ns[i];
      }
    }

    final QRDecomposition<DenseMatrix64F> qr = DecompositionFactory.qr(xs.length, 1);
    if (!qr.decompose(n)) {
      throw new IllegalArgumentException("Unable to fit to tbe USL");
    }

    final DenseMatrix64F q = qr.getQ(null, true);
    final DenseMatrix64F r = qr.getR(null, false);

    CommonOps.transpose(q);
    final DenseMatrix64F qty = new DenseMatrix64F(DEGREE, 1, false, new double[DEGREE]);
    CommonOps.mult(q, new DenseMatrix64F(xs.length, 1, true, xs), qty);

    final double[] coefficients = new double[DEGREE];
    for (int i = coefficients.length - 1; i >= 0; i--) {
      coefficients[i] = qty.get(i, 0);
      for (int j = i + 1; j < coefficients.length; j++) {
        coefficients[i] -= coefficients[j] * r.get(i, j);
      }
      coefficients[i] /= r.get(i, i);
    }

    return Model.of(abs(coefficients[2] - coefficients[1]), abs(coefficients[2]), lambda);
  }

  /**
   * The model's coefficient of contention.
   *
   * @return {@code σ}
   */
  public abstract double sigma();

  /**
   * The model's coefficient of crosstalk/coherency.
   *
   * @return {@code κ}
   */
  public abstract double kappa();

  /**
   * The model's ideal throughput.
   *
   * @return {@code λ}
   */
  public abstract double lambda();

  /**
   * The expected throughput given a number of concurrent workers.
   *
   * @param n the number of concurrent workers
   * @return {@code X(N)}
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 3"
   */
  public double throughputAtConcurrency(double n) {
    return (lambda() * n) / (1 + (sigma() * (n - 1)) + (kappa() * n * (n - 1)));
  }

  /**
   * The expected mean latency given a number of concurrent workers.
   *
   * @param n the number of concurrent workers
   * @return {@code R(N)}
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 6"
   */
  public double latencyAtConcurrency(double n) {
    return (1 + (sigma() * (n - 1)) + (kappa() * n * (n - 1))) / lambda();
  }

  /**
   * The maximum expected number of concurrent workers the system can handle.
   *
   * @return {@code N}<sub>max</sub>
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 4"
   */
  public double maxConcurrency() {
    return floor(sqrt((1 - sigma()) / kappa()));
  }

  /**
   * The maximum expected throughput the system can handle.
   *
   * @return {@code X}<sub>max</sub>
   */
  public double maxThroughput() {
    return throughputAtConcurrency(maxConcurrency());
  }

  /**
   * The expected mean latency given a throughput.
   *
   * @param x the throughput of requests
   * @return {@code R(X)}
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 8"
   */
  public double latencyAtThroughput(double x) {
    return (sigma() - 1) / (sigma() * x - lambda());
  }

  /**
   * The expected throughput given a mean latency.
   *
   * @param r the mean latency of requests
   * @return {@code X(R)}
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 9"
   */
  public double throughputAtLatency(double r) {
    final double a = 2 * kappa() * (2 * lambda() * r + sigma() - 2);
    final double b = sqrt(pow(sigma(), 2) + pow(kappa(), 2) + a);
    return (b - kappa() + sigma()) / (2.0 * kappa() * r);
  }

  /**
   * The expected number of concurrent workers given a mean latency.
   *
   * @param r the mean latency of requests
   * @return {@code N(R)}
   * @see "Practical Scalability Analysis with the Universal Scalability Law, Equation 10"
   */
  public double concurrencyAtLatency(double r) {
    final double a = (2 * kappa() * ((2 * lambda() * r) + sigma() - 2));
    final double b = sqrt(pow(sigma(), 2) + pow(kappa(), 2) + a);
    return (kappa() - sigma() + b) / (2 * kappa());
  }

  /**
   * The expected number of concurrent workers at a particular throughput.
   *
   * @param x the throughput of requests
   * @return {@code N(X)}
   */
  public double concurrencyAtThroughput(double x) {
    return latencyAtThroughput(x) * x;
  }

  /**
   * Whether or not the system is constrained by coherency costs.
   *
   * @return σ {@literal <} κ
   */
  public boolean isCoherencyConstrained() {
    return sigma() < kappa();
  }

  /**
   * Whether or not the system is constrained by contention.
   *
   * @return σ {@literal >} κ
   */
  public boolean isContentionConstrained() {
    return sigma() > kappa();
  }

  /**
   * Whether or not the system is linearly scalable.
   *
   * @return κ = 0
   */
  public boolean isLimitless() {
    return kappa() == 0;
  }
}
