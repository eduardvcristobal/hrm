package com.apexmedsuite.hrm.platform.security;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/platform-admin/backup")
public class PlatformBackupController {
    private final JdbcTemplate jdbc;
    private final String ageRecipient;
    private final String ageIdentity;
    private final Path directory;
    private final String backupDatabaseUrl;
    private final String backupDatabaseUsername;
    private final String backupDatabasePassword;

    public PlatformBackupController(JdbcTemplate jdbc,
            @Value("${app.backup.age-recipient:}") String ageRecipient,
            @Value("${app.backup.age-identity:}") String ageIdentity,
            @Value("${app.backup.directory:./data/backups}") String directory,
            @Value("${app.backup.database-url:}") String backupDatabaseUrl,
            @Value("${app.backup.database-username:}") String backupDatabaseUsername,
            @Value("${app.backup.database-password:}") String backupDatabasePassword) {
        this.jdbc=jdbc;this.ageRecipient=ageRecipient==null?"":ageRecipient.trim();
        this.ageIdentity=ageIdentity==null?"":ageIdentity.trim();this.directory=Path.of(directory).toAbsolutePath().normalize();
        this.backupDatabaseUrl=backupDatabaseUrl==null?"":backupDatabaseUrl.trim();
        this.backupDatabaseUsername=backupDatabaseUsername==null?"":backupDatabaseUsername.trim();
        this.backupDatabasePassword=backupDatabasePassword==null?"":backupDatabasePassword;
    }

    public record Backup(UUID id,String fileName,String sha256,long sizeBytes,Instant createdAt,
            String generatedByEmail,String status,Instant verifiedAt,String verifyMessage) {}
    public record RestoreCheck(String connectionString) {}
    public record RestoreTarget(String databaseName,String host,boolean empty,boolean canRestore) {}
    public record RestoreResult(String databaseName,long durationSeconds,String migrationRows,String message) {}

    @GetMapping("/history")
    public List<Backup> history() {
        return jdbc.query("SELECT id,file_name,sha256,size_bytes,created_at,generated_by_email,status,verified_at,verify_message FROM platform_backup_event ORDER BY created_at DESC LIMIT 50",
                (rs,row)->new Backup(rs.getObject("id",UUID.class),rs.getString("file_name"),rs.getString("sha256"),rs.getLong("size_bytes"),
                        rs.getTimestamp("created_at").toInstant(),rs.getString("generated_by_email"),rs.getString("status"),
                        rs.getTimestamp("verified_at")==null?null:rs.getTimestamp("verified_at").toInstant(),rs.getString("verify_message")));
    }

    @PostMapping
    public Backup create(@AuthenticationPrincipal PlatformAdminPrincipal admin) {
        requireVerifiedAdmin(admin);
        requireEncryption();
        Path work=privateTemp("apex-backup-");
        try {
            Files.createDirectories(directory);setPrivate(directory,true);
            Path dump=work.resolve("hrm.dump");
            run(databaseCommand("pg_dump", "--format=custom", "--no-owner", "--no-privileges", "--file="+dump),1800,work,backupDatabasePassword);
            String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now());
            String name="apex-hrm-backup-"+stamp+"-"+UUID.randomUUID().toString().substring(0,8)+".dump.age";
            Path archive=work.resolve(name);
            run(List.of("age","--recipient",ageRecipient,"--output",archive.toString(),dump.toString()),180,work);
            String hash=sha256(archive);long size=Files.size(archive);Path target=directory.resolve(name).normalize();
            if(!target.startsWith(directory))throw new IOException("Invalid backup destination.");
            Files.move(archive,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);setPrivate(target,false);
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO platform_backup_event(id,generated_by,generated_by_email,file_name,sha256,size_bytes,storage_path,status) VALUES(?,?,?,?,?,?,?,'CREATED')",
                    id,admin.id(),admin.email(),name,hash,size,target.toString());return find(id);
        } catch(Exception ex){throw unavailable("Backup creation failed. Check pg_dump, age, database access, and backup encryption settings.",ex);}
        finally{deleteTree(work);}
    }

    @PostMapping("/{id}/verify")
    public Backup verify(@PathVariable UUID id) {
        requireDecryption();Backup backup=require(id);Path work=privateTemp("apex-verify-");boolean valid=false;String message;
        try {Path archive=archivePath(backup);if(!sha256(archive).equalsIgnoreCase(backup.sha256()))throw new IOException("SHA-256 checksum mismatch.");Path dump=work.resolve("verify.dump");run(List.of("age","--decrypt","--identity",ageIdentity,"--output",dump.toString(),archive.toString()),300,work);String catalog=run(List.of("pg_restore","--list",dump.toString()),60,work);valid=catalog.contains("TABLE DATA")||catalog.contains("SCHEMA")||catalog.contains("DATABASE");message=valid?"Checksum matched and the PostgreSQL archive catalog is readable.":"Archive catalog is empty or invalid.";}
        catch(Exception ex){message=safe(ex);}finally{deleteTree(work);}
        jdbc.update("UPDATE platform_backup_event SET status=?,verified_at=now(),verify_message=? WHERE id=?",valid?"VERIFIED":"VERIFY_FAILED",message,id);return find(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        Backup backup=require(id);Path file=archivePath(backup);
        try {HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);headers.setContentLength(Files.size(file));headers.setContentDisposition(ContentDisposition.attachment().filename(backup.fileName()).build());return ResponseEntity.ok().headers(headers).body(new InputStreamResource(Files.newInputStream(file)));}
        catch(IOException ex){throw unavailable("Backup file is unavailable.",ex);}
    }

    @PostMapping("/restore/test-connection")
    public RestoreTarget testRestore(@RequestBody RestoreCheck input) {
        DbTarget target=parseTarget(input.connectionString());String count=runTargetUnchecked(targetCommand("psql",target,"--tuples-only","--no-align","--command",publicObjectCountSql()),60,target);
        boolean empty;try{empty=Integer.parseInt(count.trim())==0;}catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Could not verify that the database is empty.");}
        return new RestoreTarget(target.database(),target.host(),empty,empty);
    }

    @PostMapping(value="/restore",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public RestoreResult restore(@RequestParam("backup") MultipartFile upload,@RequestParam("connectionString") String connectionString) {
        requireDecryption();if(upload.isEmpty()||upload.getSize()>2L*1024*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select a non-empty encrypted backup no larger than 2 GB.");
        DbTarget target=parseTarget(connectionString);Path work=privateTemp("apex-restore-");Instant started=Instant.now();boolean startedRestore=false;
        try {Path archive=work.resolve("upload.dump.age");upload.transferTo(archive);Path dump=work.resolve("restore.dump");run(List.of("age","--decrypt","--identity",ageIdentity,"--output",dump.toString(),archive.toString()),600,work);run(List.of("pg_restore","--list",dump.toString()),60,work);String count=runTargetUnchecked(targetCommand("psql",target,"--tuples-only","--no-align","--command",publicObjectCountSql()),60,target);if(!"0".equals(count.trim()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Restore target must be an empty database.");startedRestore=true;var restore=targetCommand("pg_restore",target,"--no-owner","--no-privileges","--exit-on-error",dump.toString());runTarget(restore,1800,work,target);String migrationRows=runTargetUnchecked(targetCommand("psql",target,"--tuples-only","--no-align","--command","SELECT count(*) FROM flyway_schema_history"),60,target).trim();if(!migrationRows.matches("\\d+"))throw new IOException("Restore completed but Flyway migration history could not be checked.");return new RestoreResult(target.database(),java.time.Duration.between(started,Instant.now()).toSeconds(),migrationRows,"Backup restored into the empty target database.");}
        catch(ResponseStatusException ex){if(startedRestore)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,ex.getReason()+" Restore started; discard this partial target and use a new empty database.");throw ex;}
        catch(Exception ex){if(startedRestore)throw unavailable("Restore failed after writing to the target. Discard the partial target and use a new empty database.",ex);throw unavailable("Restore stopped before modifying the target database.",ex);}
        finally{deleteTree(work);}
    }

    private Backup find(UUID id){return jdbc.query("SELECT id,file_name,sha256,size_bytes,created_at,generated_by_email,status,verified_at,verify_message FROM platform_backup_event WHERE id=?",rs->rs.next()?new Backup(rs.getObject("id",UUID.class),rs.getString("file_name"),rs.getString("sha256"),rs.getLong("size_bytes"),rs.getTimestamp("created_at").toInstant(),rs.getString("generated_by_email"),rs.getString("status"),rs.getTimestamp("verified_at")==null?null:rs.getTimestamp("verified_at").toInstant(),rs.getString("verify_message")):null,id);}
    private Backup require(UUID id){Backup backup=find(id);if(backup==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Backup not found.");return backup;}
    private Path archivePath(Backup backup){Path file=Path.of(jdbc.queryForObject("SELECT storage_path FROM platform_backup_event WHERE id=?",String.class,backup.id())).toAbsolutePath().normalize();if(!file.startsWith(directory)||!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Backup file is missing.");return file;}
    private void requireEncryption(){if(ageRecipient.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure APP_BACKUP_AGE_RECIPIENT before creating backups.");}
    private static void requireVerifiedAdmin(PlatformAdminPrincipal admin){
        if(admin==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A platform admin session is required to create a backup.");
        if(admin.mfaEnabled()&&!admin.mfaVerified())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This platform admin session is not MFA-verified. Verify it in Account security before creating a backup.");
    }
    private void requireDecryption(){if(ageIdentity.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure APP_BACKUP_AGE_IDENTITY before verifying or restoring backups.");}
    private List<String> databaseCommand(String executable,String...args){String url=backupDatabaseUrl;if(!url.startsWith("jdbc:postgresql://"))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure APP_BACKUP_DB_URL for the dedicated backup database role.");if(backupDatabaseUsername.isBlank()||backupDatabasePassword.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure APP_BACKUP_DB_USERNAME and APP_BACKUP_DB_PASSWORD for the dedicated backup role.");URI uri=URI.create(url.substring(5));String db=uri.getPath()==null?"":uri.getPath().replaceFirst("^/","");if(db.isBlank()||uri.getHost()==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Backup database name or host is missing.");java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add(executable);command.add("--host");command.add(uri.getHost());command.add("--port");command.add(Integer.toString(uri.getPort()<0?5432:uri.getPort()));command.add("--username");command.add(backupDatabaseUsername);command.add("--dbname");command.add(db);command.addAll(List.of(args));return command;}
    private String run(List<String> command,long timeoutSeconds,Path work)throws Exception{return run(command,timeoutSeconds,work,"");}
    private String runTarget(List<String> command,long timeoutSeconds,Path work,DbTarget target)throws Exception{return run(command,timeoutSeconds,work,target.password());}
    private String run(List<String> command,long timeoutSeconds,Path work,String password)throws Exception{Files.createDirectories(work);Path outputFile=Files.createTempFile(work,"process-",".log");setPrivate(outputFile,false);ProcessBuilder builder=new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile());if(password!=null&&!password.isBlank())builder.environment().put("PGPASSWORD",password);Process process=builder.start();if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new IOException("Process timed out: "+command.get(0));}String output=readTail(outputFile,16_000);if(process.exitValue()!=0)throw new IOException(command.get(0)+" failed: "+output.trim());return output;}
    private String runTargetUnchecked(List<String> command,long timeout,DbTarget target){Path work=privateTemp("apex-db-check-");try{return runTarget(command,timeout,work,target);}catch(Exception ex){throw unavailable("Could not inspect PostgreSQL restore target.",ex);}finally{deleteTree(work);}}
    private static List<String> targetCommand(String executable,DbTarget target,String...args){java.util.ArrayList<String> command=new java.util.ArrayList<>(List.of(executable,"--host",target.host(),"--port",Integer.toString(target.port())));if(target.username()!=null&&!target.username().isBlank()){command.add("--username");command.add(target.username());}command.add("--dbname");command.add(target.database());command.addAll(List.of(args));return command;}
    private static String publicObjectCountSql(){return "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f')";}
    private static DbTarget parseTarget(String value){try{URI uri=URI.create(value.trim());if(!List.of("postgresql","postgres").contains(uri.getScheme())||uri.getHost()==null||uri.getPath()==null||uri.getPath().length()<2)throw new IllegalArgumentException();String userInfo=uri.getUserInfo();String username=userInfo==null?null:userInfo.split(":",2)[0];String password=userInfo==null||!userInfo.contains(":")?"":userInfo.substring(userInfo.indexOf(':')+1);return new DbTarget(uri.getHost(),uri.getPort()<0?5432:uri.getPort(),uri.getPath().substring(1),username,password);}catch(Exception ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid postgresql:// connection string.");}}
    private record DbTarget(String host,int port,String database,String username,String password){}
    private static Path privateTemp(String prefix){try{Path path=Files.createTempDirectory(prefix);setPrivate(path,true);return path;}catch(IOException ex){throw unavailable("Could not create a private temporary folder.",ex);}}
    private static void setPrivate(Path path,boolean directory){try{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}catch(UnsupportedOperationException|IOException ignored){}}
    private static void deleteTree(Path root){if(root==null)return;try(var paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
    private static String sha256(Path file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[1024*1024];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}
    private static String readTail(Path file,int limit)throws IOException{long size=Files.size(file);try(var input=Files.newInputStream(file)){input.skipNBytes(Math.max(0,size-limit));byte[] data=input.readNBytes(limit);return new String(data,java.nio.charset.StandardCharsets.UTF_8);}}
    private static String safe(Exception ex){return ex.getMessage()==null?"Operation failed.":ex.getMessage().replaceAll("(?i)(password|token|secret)=\\S+","$1=[redacted]");}
    private static ResponseStatusException unavailable(String message,Exception cause){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,message+" "+safe(cause));}
}
