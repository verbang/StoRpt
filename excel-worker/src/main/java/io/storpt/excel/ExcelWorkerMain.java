package io.storpt.excel;

/** Executable entry point. Reads one JSON request from stdin and writes one JSON response. */
public final class ExcelWorkerMain {
  private ExcelWorkerMain() {}

  public static void main(String[] args) {
    int exitCode = new WorkerCli().run(System.in, System.out);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }
}
