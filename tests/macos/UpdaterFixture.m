// Disposable, headless integration app. Never uses Hoppair's bundle ID or data.
#import <AppKit/AppKit.h>
#import <Sparkle/Sparkle.h>

static NSString *rootPath;
static NSString *mode;
static int resultCode;

static void record(NSString *event, NSDictionary *details) {
    NSMutableDictionary *row = [@{@"event": event, @"pid": @(getpid())} mutableCopy];
    [row addEntriesFromDictionary:details ?: @{}];
    NSData *json = [NSJSONSerialization dataWithJSONObject:row options:0 error:nil];
    NSString *path = [rootPath stringByAppendingPathComponent:@"events.jsonl"];
    if (![[NSFileManager defaultManager] fileExistsAtPath:path]) [[NSData data] writeToFile:path atomically:YES];
    NSFileHandle *file = [NSFileHandle fileHandleForWritingAtPath:path];
    [file seekToEndOfFile]; [file writeData:json]; [file writeData:[@"\n" dataUsingEncoding:NSUTF8StringEncoding]]; [file closeFile];
}

static void finish(NSString *event, NSDictionary *details, int code) {
    record(event, details); resultCode = code;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), dispatch_get_main_queue(), ^{ [NSApp terminate:nil]; });
}

@interface Fixture : NSObject <NSApplicationDelegate, SPUUserDriver, SPUUpdaterDelegate>
@property(strong) SPUUpdater *updater;
@property(copy) void (^cancelDownload)(void);
@property BOOL cancelled;
@property BOOL failed;
@end

@implementation Fixture
- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    NSDictionary *info = NSBundle.mainBundle.infoDictionary;
    rootPath = info[@"HoppairTestRoot"]; mode = info[@"HoppairTestMode"];
    if (![NSBundle.mainBundle.bundleIdentifier hasPrefix:@"dev.hoppair.updater-test."] || !rootPath) { exit(2); }
    record(@"launched", @{@"build": info[@"CFBundleVersion"]});
    if ([info[@"CFBundleVersion"] isEqual:@"2"]) { finish(@"relaunched", @{@"build": @"2"}, 0); return; }
    self.updater = [[SPUUpdater alloc] initWithHostBundle:NSBundle.mainBundle applicationBundle:NSBundle.mainBundle userDriver:self delegate:self];
    NSError *error;
    if (![self.updater startUpdater:&error]) { finish(@"startup-error", @{@"error": error.description}, 1); return; }
    [self.updater checkForUpdates];
}
- (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender { record(@"terminating", nil); return NSTerminateNow; }
- (void)showUpdatePermissionRequest:(SPUUpdatePermissionRequest *)request reply:(void (^)(SUUpdatePermissionResponse *))reply {
    reply([[SUUpdatePermissionResponse alloc] initWithAutomaticUpdateChecks:NO sendSystemProfile:NO]);
}
- (void)showUserInitiatedUpdateCheckWithCancellation:(void (^)(void))cancellation { record(@"checking", nil); }
- (void)showUpdateFoundWithAppcastItem:(SUAppcastItem *)item state:(SPUUserUpdateState *)state reply:(void (^)(SPUUserUpdateChoice))reply {
    record(@"found", @{@"signatureValid": item.signingValidationStatus == SPUAppcastSigningValidationStatusSucceeded ? @YES : @NO});
    if (item.signingValidationStatus != SPUAppcastSigningValidationStatusSucceeded || [mode isEqual:@"tampered"]) {
        self.failed = YES; reply(SPUUserUpdateChoiceSkip); finish(@"unexpected-feed-acceptance", nil, 1); return;
    }
    reply(SPUUserUpdateChoiceInstall);
}
- (void)showUpdateReleaseNotesWithDownloadData:(SPUDownloadData *)data {}
- (void)showUpdateReleaseNotesFailedToDownloadWithError:(NSError *)error {}
- (void)showUpdateNotFoundWithError:(NSError *)error acknowledgement:(void (^)(void))acknowledgement {
    self.failed = YES; acknowledgement(); finish(@"unexpected-no-update", nil, 1);
}
- (void)showUpdaterError:(NSError *)error acknowledgement:(void (^)(void))acknowledgement {
    self.failed = YES;
    NSMutableArray *chain = [NSMutableArray array];
    BOOL signatureFailure = NO;
    for (NSError *current = error; current && chain.count < 12; current = current.userInfo[NSUnderlyingErrorKey]) {
        [chain addObject:@{@"domain": current.domain, @"code": @(current.code), @"description": current.localizedDescription}];
        if ([current.domain isEqual:SUSparkleErrorDomain] &&
            (current.code == SUSignatureError || current.code == SUValidationError) &&
            [current.localizedDescription containsString:@"signature does not match"]) signatureFailure = YES;
    }
    record(@"updater-error", @{@"chain": chain}); acknowledgement();
    BOOL expected = ([mode isEqual:@"tampered"] || [mode isEqual:@"payload-tampered"]) && signatureFailure;
    finish(expected ? @"tamper-rejected" : @"unexpected-error", nil, expected ? 0 : 1);
}
- (void)showDownloadInitiatedWithCancellation:(void (^)(void))cancellation { self.cancelDownload = cancellation; record(@"download-started", nil); }
- (void)showDownloadDidReceiveExpectedContentLength:(uint64_t)length { record(@"expected-bytes", @{@"bytes": @(length)}); }
- (void)showDownloadDidReceiveDataOfLength:(uint64_t)length {
    if ([mode isEqual:@"cancel"] && !self.cancelled) { self.cancelled = YES; record(@"download-cancelled", nil); self.cancelDownload(); }
}
- (void)showDownloadDidStartExtractingUpdate { record(@"extracting", nil); }
- (void)showExtractionReceivedProgress:(double)progress {}
- (void)showReadyToInstallAndRelaunch:(void (^)(SPUUserUpdateChoice))reply { record(@"ready", nil); reply(SPUUserUpdateChoiceInstall); }
- (void)showInstallingUpdateWithApplicationTerminated:(BOOL)terminated retryTerminatingApplication:(void (^)(void))retry { record(@"installing", nil); }
- (void)showUpdateInstalledAndRelaunched:(BOOL)relaunched acknowledgement:(void (^)(void))acknowledgement { record(@"installed", @{@"relaunched": @(relaunched)}); acknowledgement(); }
- (void)dismissUpdateInstallation {
    self.cancelDownload = nil;
    if (self.cancelled && !self.failed) finish(@"cancel-completed", nil, 0);
}
- (void)updaterWillRelaunchApplication:(SPUUpdater *)updater { record(@"will-relaunch", nil); }
// Sparkle 2.10 calls this when the installer helper starts, before its validation.
- (void)updater:(SPUUpdater *)updater didExtractUpdate:(SUAppcastItem *)item { record(@"installer-started", nil); }
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSApplication *app = NSApplication.sharedApplication;
        Fixture *delegate = [Fixture new]; app.delegate = delegate;
        [app setActivationPolicy:NSApplicationActivationPolicyProhibited]; [app run];
    }
    return resultCode;
}
