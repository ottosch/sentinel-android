package com.samourai.boltzmann;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.math.DoubleMath;
import com.samourai.boltzmann.beans.BoltzmannResult;
import com.samourai.boltzmann.beans.BoltzmannSettings;
import com.samourai.boltzmann.beans.NoLimitSettings;
import com.samourai.boltzmann.utils.LogbackUtils;
import com.samourai.boltzmann.utils.Progress;
import com.samourai.boltzmann.utils.Utils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.event.Level;

/**
 * CLI entry point. The wallet report is private to this class so the library
 * API cannot dump inputs, outputs, fees or the export JSON (#564, #576).
 */
public class Application {

  public static void main(String[] args) throws Exception {
    // TODO dynamic toggle for debug
    LogbackUtils.setLogLevel("com.samourai.boltzmann", Level.DEBUG.toString());

    // parse args
    if (args.length < 1) {
      System.err.println("Usage: <txid> [maxCjIntrafeesRatio]");
      return;
    }
    String txid = args[0];
    float maxCjIntrafeesRatio = 0.005f;
    if (args.length > 1) {
      maxCjIntrafeesRatio = Float.parseFloat(args[1]);
    }
    System.out.println(
        "Running Boltzmann: txid=" + txid + ", maxCjIntrafeesRatio=" + maxCjIntrafeesRatio);

    // run
    BoltzmannSettings settings = new NoLimitSettings();
    settings.setMaxCjIntrafeesRatio(maxCjIntrafeesRatio);
    print(new Boltzmann(settings).process(txid));
  }

  /** Stdout report for this CLI only. Not part of the library API. */
  private static void print(BoltzmannResult result) {

    System.out.println("Inputs = " + result.getTxos().getInputs());
    System.out.println("Outputs = " + result.getTxos().getOutputs());
    System.out.println("Fees = " + result.getFees() + " satoshis");

    if (result.getIntraFees() != null
        && result.getIntraFees().getFeesMaker() > 0
        && result.getIntraFees().getFeesTaker() > 0) {
      System.out.println(
          "Hypothesis: Max intrafees received by a participant = "
              + result.getIntraFees().getFeesMaker()
              + " satoshis");
      System.out.println(
          "Hypothesis: Max intrafees paid by a participant = "
              + result.getIntraFees().getFeesTaker()
              + " satoshis");
    }

    if (result.getNbCmbnPrfctCj() != null) {
      System.out.println(
          "Perfect coinjoin = "
              + result.getNbCmbnPrfctCj()
              + " combinations (for "
              + result.getNbTxosPrfctCj().getNbIns()
              + "x"
              + result.getNbTxosPrfctCj().getNbOuts()
              + ")");
    }
    System.out.println("Nb combinations = " + result.getNbCmbn());
    if (result.getEntropy() != null) {
      System.out.println("Tx entropy = " + result.getEntropy() + " bits");
      System.out.println("Entropy denstity = " + result.getDensity());
    }

    if (result.getEfficiency() != null) {
      System.out.println(
          "Wallet efficiency = "
              + (result.getEfficiency() * 100)
              + "% ("
              + DoubleMath.log2(result.getEfficiency())
              + " bits)");
    }

    if (result.getMatLnkCombinations() == null) {
      if (result.getNbCmbn() == 0) {
        System.out.println(
            "Skipped processing of this transaction (too many inputs and/or outputs)");
      }
    } else {
      if (result.getMatLnkProbabilities() != null) {
        System.out.println("Linkability Matrix (probabilities):");
        System.out.println(result.getMatLnkProbabilities());
      }
      if (result.getMatLnkCombinations() != null) {
        System.out.println("Linkability Matrix (#combinations with link):");
        System.out.println(result.getMatLnkCombinations());
      }
    }

    if (result.getDtrmLnks() == null) {
      System.out.println("Deterministic links: all");
    } else if (result.getDtrmLnks().length > 0) {
      System.out.println("Deterministic links:");
      String[][] readableDtrmLnks = result.getDtrmLnks();
      for (String[] dtrmLink : readableDtrmLnks) {
        String out = dtrmLink[0];
        String in = dtrmLink[1];
        System.out.println(in + " & " + out + " are deterministically linked");
      }
    } else {
      System.out.println("Deterministic links: none");
    }

    System.out.println("Benchmarks:");
    List<Object[]> benchmarks = new ArrayList<Object[]>();

    benchmarks.add(new Object[] {"duration", result.getDuration()});
    System.out.println("Duration = " + Utils.duration(result.getDuration()));

    long maxMem = Utils.getMaxMemUsed();
    benchmarks.add(new Object[] {"maxMem", maxMem});
    System.out.println("Max mem used: " + maxMem + "M");

    for (Progress progress : Utils.getProgressResult()) {
      System.out.println(progress.getResult());
      Object[] row =
          new Object[] {
            progress.getName(),
            progress.getTarget(),
            progress.computeElapsed() / 1000,
            progress.getRate(),
            progress.getMsg()
          };
      benchmarks.add(row);
    }

    try {
      Map export = new LinkedHashMap();
      export.put("ins", result.getTxos().getInputs());
      export.put("outs", result.getTxos().getOutputs());
      export.put("nbCmbn", result.getNbCmbn());
      export.put(
          "mat",
          result.getMatLnkCombinations() != null
              ? result.getMatLnkCombinations().toString()
              : null); // null => entropy=0 & matrix full of 1
      export.put("benchmarks", benchmarks);

      String exportStr = new ObjectMapper().writeValueAsString(export);
      System.out.println("Export: " + exportStr);
    } catch (Exception e) {
      e.printStackTrace();
    }
    }
}
