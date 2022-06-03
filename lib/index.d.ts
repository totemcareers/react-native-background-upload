import { AddListener, FileInfo, MultipartUploadOptions, UploadOptions } from 'types';
export declare const getFileInfo: (path: string) => Promise<FileInfo>;
export declare const startUpload: (options: UploadOptions | MultipartUploadOptions) => Promise<string>;
export declare const cancelUpload: (cancelUploadId: string) => Promise<boolean>;
export declare const addListener: AddListener;
declare const _default: {
    startUpload: (options: UploadOptions | MultipartUploadOptions) => Promise<string>;
    cancelUpload: (cancelUploadId: string) => Promise<boolean>;
    addListener: AddListener;
    getFileInfo: (path: string) => Promise<FileInfo>;
};
export default _default;
