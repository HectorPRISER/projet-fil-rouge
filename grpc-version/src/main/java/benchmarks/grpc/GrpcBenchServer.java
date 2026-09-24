package benchmarks.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

// Serveur gRPC dedie au benchmark protocole-a-protocole avec ghz : expose uniquement le
// RPC unaire ScanOne (1 bloc = 1 appel), pour une comparaison directe avec RestBenchServer
// (/scan) sous charge equivalente (vegeta pour REST, ghz pour gRPC).
//
// Lancement : java -cp target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcBenchServer <port>
public class GrpcBenchServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);

        CrackServiceGrpc.AsyncService service = new CrackServiceGrpc.AsyncService() {
            @Override
            public void scanOne(CrackProto.Block block, StreamObserver<CrackProto.WorkerReport> responseObserver) {
                try {
                    byte[] targetHash = CrackUtil.decodeHex(block.getTargetHash());
                    CrackUtil.ScanResult result = CrackUtil.scanBlock(
                            block.getLength(), block.getFirstChar().charAt(0), targetHash);
                    CrackProto.WorkerReport.Builder report = CrackProto.WorkerReport.newBuilder()
                            .setLength(block.getLength())
                            .setFirstChar(block.getFirstChar())
                            .setAttempts(result.attempts());
                    if (result.found() != null) {
                        report.setFound(result.found());
                    }
                    responseObserver.onNext(report.build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    responseObserver.onError(e);
                }
            }
        };

        Server server = ServerBuilder.forPort(port)
                .addService(CrackServiceGrpc.bindService(service))
                .build().start();
        System.out.printf("GrpcBenchServer en ecoute sur le port %d (ScanOne)%n", port);
        server.awaitTermination();
    }
}
