import { IconBase, IconProps } from '../icon';
export default class CopyrightIcon extends IconBase<IconProps> {
    static icon: {
        attributes: {
            fill: string;
            viewBox: string;
            width: string;
            height: string;
        };
        nodeName: string;
        children: {
            attributes: {
                id: string;
            };
            nodeName: string;
            children: ({
                attributes: {
                    id: string;
                    fill: string;
                    d: string;
                    stroke?: undefined;
                    "stroke-linecap"?: undefined;
                    "stroke-width"?: undefined;
                };
                nodeName: string;
                children: never[];
            } | {
                attributes: {
                    id: string;
                    stroke: string;
                    d: string;
                    "stroke-linecap": string;
                    "stroke-width": number;
                    fill?: undefined;
                };
                nodeName: string;
                children: never[];
            })[];
        }[];
    };
    constructor();
}
