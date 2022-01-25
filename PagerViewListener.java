ViewPager.OnPageChangeListener beholder = new ViewPager.OnPageChangeListener() {
    @Override
    public void onPageScrolled(int i, float v, int i1) {
        //computing color for background based on (v) and colors array for every frame
        int nextColor = mFlyers.get((i+1)%mFlyers.size()).getmColor();
        int currentColor = mFlyers.get((i)%mFlyers.size()).getmColor();

        float currentRed = currentColor >> 16 & 0xff;
        float currentGreen = currentColor >> 8 & 0xff;
        float currentBlue = currentColor & 0xff;
        float nextRed = nextColor >> 16 & 0xff;
        float nextGreen = nextColor >> 8 & 0xff;
        float nextBlue = nextColor & 0xff;

        int newRed = (int) (currentRed + ((nextRed - currentRed) * v));
        int newGreen = (int) (currentGreen + ((nextGreen - currentGreen) * v));
        int newBlue = (int) (currentBlue + ((nextBlue - currentBlue) * v));

        //setting color
        mPainter.paintBackgroundNative(Color.rgb(newRed, newGreen, newBlue));
    }

    @Override
    public void onPageSelected(int i) {
        if(i==mFlyers.size()-1){
            //Sets the button visible
            mLetsgoBtn.setVisibility(View.VISIBLE);
            dotContainer.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPageScrollStateChanged(int i) {

    }
};