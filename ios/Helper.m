#import <Foundation/Foundation.h>

#import "Helper.h"

@implementation Helper



+ (NSString *) urlSessionTaskStateToString: (NSURLSessionTaskState)state {
    switch (state) {
        case NSURLSessionTaskStateRunning:
            return @"running";
        case NSURLSessionTaskStateSuspended:
            return @"suspended";
        case NSURLSessionTaskStateCompleted:
            return @"completed";
        case NSURLSessionTaskStateCanceling:
            return @"canceling";
        default:
            return NULL;
    }
}

@end

